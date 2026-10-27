package rw.terimbere.csams.modules.rotation.service;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnInput;
import rw.terimbere.csams.modules.rotation.entity.RotationTurnMode;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * The third migration workbook, imported after the members and share-capital workbook (and the loans workbook): what a
 * scheme that was already running carries in besides capital and loans.
 *
 * <ul>
 *   <li><b>Scheme</b>: the running rotation cycle (first month, months, payout per share, rotated amount per share,
 *       how shares are paid), the rotation money held at the cutover, and income earned before the cutover not yet
 *       shared out.
 *   <li><b>Turns</b>: who is paid in which month, how many shares, and what each turn already received.
 *   <li><b>Members</b>: rotated money each member paid before the cutover, and what the scheme owes the member.
 * </ul>
 *
 * An accumulating scheme leaves the rotation rows empty and uses only the owed amounts and the unshared income.
 */
@Service
@RequiredArgsConstructor
public class OpeningPositionsWorkbookService {

    public static final String SHEET_SCHEME = "Scheme";
    public static final String SHEET_TURNS = "Turns";
    public static final String SHEET_MEMBERS = "Members";

    static final String S_START = "Cycle first month (YYYY-MM)";
    static final String S_MONTHS = "Months in the cycle";
    static final String S_PAYOUT = "Payout per share (RWF)";
    static final String S_ROTATED = "Rotated per share each month (RWF)";
    static final String S_MODE = "How shares are paid (ONE_SHARE_PER_TURN, ALL_SHARES_TOGETHER or GROUPED_BY_MONTH)";
    static final String S_CASH = "Rotation money held at the cutover (RWF)";
    static final String S_INCOME = "Income earned before the cutover not yet shared (RWF)";
    static final String S_FIRST = "First month collected in the app (YYYY-MM), e.g. 2026-09 for an August cut-off";

    static final List<String> TURN_HEADERS = List.of(
            "Username", "Turn Month (YYYY-MM)", "Shares", "Amount Received Before Cutover (RWF)", "Date Received");
    static final List<String> MEMBER_HEADERS = List.of(
            "Username", "Rotated Money Paid Before Cutover (RWF)", "Amount the Scheme Owes the Member (RWF)");

    private final RotationAccess access;
    private final RotationMigrationService migrationService;
    private final MemberOpeningBalanceRepository openingRepository;
    private final UserRepository userRepository;
    private final CooperativeRepository cooperativeRepository;
    private final SchemeFundLockService fundLockService;
    private final JdbcTemplate jdbc;
    private final OpeningWorkbookRevisionService revisions;
    private final rw.terimbere.csams.shared.file.FileStorageService fileStorageService;
    private final rw.terimbere.csams.modules.audit.service.AuditService auditService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** One upload of the workbook: the current one, or one a correction replaced. */
    public record UploadRecord(
            UUID id,
            int version,
            String status,
            String originalFilename,
            UUID uploadedBy,
            String uploadedByName,
            java.time.Instant uploadedAt,
            String revisionReason,
            java.time.Instant supersededAt,
            String summary) {}

    public record ImportResult(
            int membersUpdated,
            BigDecimal totalOwedToMembers,
            BigDecimal unallocatedIncome,
            boolean rotationImported,
            RotationMigrationService.RotationImportResponse rotation,
            List<String> warnings) {}

    // ------------------------------------------------------------------ template

    public byte[] template(UUID cooperativeId) {
        access.requireManage(cooperativeId);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Sheet scheme = wb.createSheet(SHEET_SCHEME);
            header(scheme, List.of("Setting", "Value"), bold);
            int r = 1;
            for (String key : List.of(S_START, S_MONTHS, S_PAYOUT, S_ROTATED, S_MODE, S_CASH, S_INCOME, S_FIRST)) {
                scheme.createRow(r++).createCell(0).setCellValue(key);
            }
            scheme.setColumnWidth(0, 100 * 256);
            scheme.setColumnWidth(1, 24 * 256);
            header(wb.createSheet(SHEET_TURNS), TURN_HEADERS, bold);
            header(wb.createSheet(SHEET_MEMBERS), MEMBER_HEADERS, bold);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the template", e);
        }
    }

    private static void header(Sheet sheet, List<String> headers, CellStyle bold) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = row.createCell(i);
            c.setCellValue(headers.get(i));
            c.setCellStyle(bold);
            sheet.setColumnWidth(i, Math.max(16, headers.get(i).length() + 4) * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    // ------------------------------------------------------------------ import

    public ImportResult importWorkbook(UUID cooperativeId, MultipartFile file, HttpServletRequest http) {
        return importWorkbook(cooperativeId, file, null, http);
    }

    /** Every upload of the workbook for the scheme, newest first. */
    @Transactional(readOnly = true)
    public List<UploadRecord> history(UUID cooperativeId) {
        access.requireManage(cooperativeId);
        return jdbc.query("select o.id, o.version_number, o.status, o.original_filename, o.uploaded_by,"
                        + " trim(coalesce(u.first_name, '') || ' ' || coalesce(u.last_name, '')) as who, o.created_at,"
                        + " o.revision_reason, o.superseded_at, o.summary from opening_workbook_imports o left join users u on u.id = o.uploaded_by"
                        + " where o.cooperative_id = ? order by o.version_number desc",
                (rs, i) -> new UploadRecord(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class),
                        rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getString(10)),
                cooperativeId);
    }

    /**
     * Imports the workbook. When it was imported before, this upload is a correction: it replaces the earlier one
     * (which stays on record as SUPERSEDED) and needs the reason for it.
     */
    @Transactional
    public ImportResult importWorkbook(UUID cooperativeId, MultipartFile file, String reason, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        OpeningWorkbookRevisionService.Current current = revisions.current(cooperativeId);
        String cleanReason = reason == null ? "" : reason.trim();
        if (current != null && cleanReason.length() < 10) {
            throw new BusinessException("REVISION_REASON_REQUIRED",
                    "This workbook was imported before: explain what this corrected upload fixes (at least 10 characters)");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("The file could not be read", Map.of("file", "Upload it again"));
        }
        Map<String, String> settings;
        List<Map<String, String>> turns;
        List<Map<String, String>> members;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes); Workbook wb = new XSSFWorkbook(in)) {
            settings = readSettings(wb.getSheet(SHEET_SCHEME));
            turns = readRows(wb.getSheet(SHEET_TURNS), TURN_HEADERS);
            members = readRows(wb.getSheet(SHEET_MEMBERS), MEMBER_HEADERS);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException v) {
                throw v;
            }
            throw new ValidationException("The file is not a readable rotation and positions workbook",
                    Map.of("file", "Download the template and fill it in"));
        }

        OpeningWorkbookRevisionService.Undone undone = null;
        if (current != null) {
            String rotatedText = settings.get(S_ROTATED);
            BigDecimal rotatedUnit = null;
            try {
                rotatedUnit = rotatedText == null || rotatedText.isBlank() ? null : new BigDecimal(rotatedText.replace(",", "").trim());
            } catch (NumberFormatException ignored) {
                // reported below with the other problems of the workbook
            }
            undone = revisions.undo(cooperativeId, current, rotatedUnit);
        }

        Map<String, UUID> ids = new HashMap<>();
        Map<UUID, MemberOpeningBalance> openings = new HashMap<>();
        for (MemberOpeningBalance o : openingRepository.findByCooperativeId(cooperativeId)) {
            openings.put(o.getMemberUserId(), o);
        }
        for (User u : userRepository.findAllById(openings.keySet())) {
            ids.put(u.getUsername().toLowerCase(Locale.ROOT), u.getId());
        }
        if (openings.isEmpty()) {
            throw new BusinessException("OPENING_BALANCES_MISSING",
                    "Import the members and share-capital workbook first; this workbook completes it");
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Members: owed amounts and rotated money paid.
        Map<UUID, BigDecimal> rotatedPaid = new LinkedHashMap<>();
        BigDecimal owedTotal = BigDecimal.ZERO;
        int updated = 0;
        for (Map<String, String> row : members) {
            UUID id = ids.get(row.get(MEMBER_HEADERS.get(0)).toLowerCase(Locale.ROOT));
            if (id == null) {
                errors.add("Members row " + row.get("#") + ": unknown username " + row.get(MEMBER_HEADERS.get(0)));
                continue;
            }
            BigDecimal paid = money(row.get(MEMBER_HEADERS.get(1)), "Members row " + row.get("#"), errors);
            BigDecimal owed = money(row.get(MEMBER_HEADERS.get(2)), "Members row " + row.get("#"), errors);
            rotatedPaid.put(id, paid);
            MemberOpeningBalance o = openings.get(id);
            o.setOpeningOwedToMember(owed);
            openingRepository.save(o);
            owedTotal = owedTotal.add(owed);
            updated++;
        }

        BigDecimal income = money(settings.get(S_INCOME), "Scheme: " + S_INCOME, errors);
        boolean hasRotation = settings.get(S_START) != null && !settings.get(S_START).isBlank();
        ShareCycleCreateRequest cycle = null;
        BigDecimal rotated = null;
        BigDecimal cash = null;
        if (hasRotation) {
            SchemeOperatingModel model = cooperativeRepository.findOperatingModelById(cooperativeId);
            if (model != SchemeOperatingModel.ROTATING && model != SchemeOperatingModel.HYBRID) {
                errors.add("Scheme: this scheme is not Rotating or Hybrid, leave the cycle rows empty");
            }
            YearMonth start = month(settings.get(S_START), "Scheme: " + S_START, errors);
            int months = (int) money(settings.get(S_MONTHS), "Scheme: " + S_MONTHS, errors).longValue();
            BigDecimal payout = money(settings.get(S_PAYOUT), "Scheme: " + S_PAYOUT, errors);
            rotated = settings.get(S_ROTATED) == null || settings.get(S_ROTATED).isBlank()
                    ? null : money(settings.get(S_ROTATED), "Scheme: " + S_ROTATED, errors);
            cash = settings.get(S_CASH) == null || settings.get(S_CASH).isBlank()
                    ? null : money(settings.get(S_CASH), "Scheme: " + S_CASH, errors);
            RotationTurnMode mode = RotationTurnMode.GROUPED_BY_MONTH;
            String modeText = settings.get(S_MODE);
            if (modeText != null && !modeText.isBlank()) {
                try {
                    mode = RotationTurnMode.valueOf(modeText.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
                } catch (IllegalArgumentException e) {
                    errors.add("Scheme: unknown way of paying shares " + modeText);
                }
            }
            List<TurnInput> inputs = new ArrayList<>();
            Map<String, TurnInput> merged = new LinkedHashMap<>();
            for (Map<String, String> row : turns) {
                String where = "Turns row " + row.get("#");
                UUID id = ids.get(row.get(TURN_HEADERS.get(0)).toLowerCase(Locale.ROOT));
                if (id == null) {
                    errors.add(where + ": unknown username " + row.get(TURN_HEADERS.get(0)));
                    continue;
                }
                YearMonth m = month(row.get(TURN_HEADERS.get(1)), where, errors);
                if (start == null || m == null) {
                    continue;
                }
                int index = (int) (m.getYear() * 12L + m.getMonthValue() - (start.getYear() * 12L + start.getMonthValue())) + 1;
                if (index < 1 || index > months) {
                    errors.add(where + ": " + m + " is outside the cycle");
                    continue;
                }
                int shares = (int) money(row.get(TURN_HEADERS.get(2)), where, errors).longValue();
                BigDecimal received = money(row.get(TURN_HEADERS.get(3)), where, errors);
                LocalDate date = date(row.get(TURN_HEADERS.get(4)), where, errors);
                String key = index + ":" + id;
                TurnInput prev = merged.get(key);
                if (prev != null) { // two rows for one member in one month are one turn
                    merged.put(key, new TurnInput(index, id, prev.shares() + shares,
                            nz(prev.openingReceivedAmount()).add(received), date != null ? date : prev.openingReceivedDate()));
                } else {
                    merged.put(key, new TurnInput(index, id, shares, received, date));
                }
            }
            inputs.addAll(merged.values());
            if (start != null && months > 0 && payout.signum() > 0) {
                cycle = new ShareCycleCreateRequest(start.getYear(), start.getMonthValue(), months, payout, mode, inputs);
            } else {
                errors.add("Scheme: the cycle needs its first month, its number of months and the payout per share");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The workbook has " + errors.size() + " problem(s)",
                    Map.of("workbook", String.join("; ", errors.subList(0, Math.min(errors.size(), 25)))));
        }

        jdbc.update("insert into scheme_opening_positions (cooperative_id, unallocated_income, updated_by, updated_at)"
                        + " values (?, ?, ?, now()) on conflict (cooperative_id) do update set unallocated_income = excluded.unallocated_income,"
                        + " updated_by = excluded.updated_by, updated_at = now()",
                cooperativeId, MoneyUtils.scaleForStorage(income), principal.getId());

        RotationMigrationService.RotationImportResponse rotation = null;
        YearMonth firstCollected = null;
        if (cycle != null) {
            YearMonth now = access.currentMonth();
            String firstText = settings.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("First month collected in the app"))
                    .map(Map.Entry::getValue).findFirst().orElse("");
            if (!firstText.isBlank()) {
                List<String> problems = new ArrayList<>();
                YearMonth first = month(firstText, "Scheme: first month collected in the app", problems);
                if (!problems.isEmpty()) {
                    throw new ValidationException("The workbook has a problem", Map.of("workbook", problems.get(0)));
                }
                now = first;
            }
            List<RotationMigrationService.MemberRotationOpening> openingsIn = new ArrayList<>();
            rotatedPaid.forEach((id, paid) -> openingsIn.add(new RotationMigrationService.MemberRotationOpening(id, paid, null)));
            firstCollected = now;
            rotation = migrationService.importRotation(cooperativeId, new RotationMigrationService.RotationImportRequest(
                    now.getYear(), now.getMonthValue(), rotated, cycle, openingsIn, cash), http);
            warnings.addAll(rotation.warnings());
        }
        UUID newCycle = rotation == null || rotation.cycle() == null ? null : rotation.cycle().id();
        if (undone != null) {
            revisions.moveToCorrectedCycle(cooperativeId, undone, newCycle);
        }
        ImportResult result = new ImportResult(updated, MoneyUtils.scaleForStorage(owedTotal), MoneyUtils.scaleForStorage(income),
                rotation != null, rotation, warnings);
        record(cooperativeId, principal.getId(), file, bytes, current, cleanReason, newCycle, firstCollected, result, http);
        return result;
    }

    private void record(UUID cooperativeId, UUID actor, MultipartFile file, byte[] bytes, OpeningWorkbookRevisionService.Current current,
            String reason, UUID cycleId, YearMonth first, ImportResult result, HttpServletRequest http) {
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String key = "opening-workbooks/" + cooperativeId + "/" + UUID.randomUUID() + ".xlsx";
        try {
            fileStorageService.store(key, new java.io.ByteArrayInputStream(bytes), bytes.length,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (RuntimeException e) {
            key = null; // the import stands; only the stored copy of the file is missing
        }
        String summary = "{\"membersUpdated\":" + result.membersUpdated() + ",\"owedToMembers\":\"" + result.totalOwedToMembers().toPlainString()
                + "\",\"unallocatedIncome\":\"" + result.unallocatedIncome().toPlainString() + "\",\"rotationImported\":" + result.rotationImported()
                + (result.rotation() == null ? "" : ",\"rotatedPaid\":\"" + result.rotation().totalRotatedPaid().toPlainString()
                        + "\",\"received\":\"" + result.rotation().totalReceived().toPlainString() + "\"") + "}";
        UUID id = UUID.randomUUID();
        if (current != null) {
            jdbc.update("update opening_workbook_imports set status = 'SUPERSEDED', superseded_at = now() where id = ?", current.id());
        }
        String name = file.getOriginalFilename();
        jdbc.update("insert into opening_workbook_imports (id, cooperative_id, version_number, status, original_filename, file_hash, storage_key,"
                        + " cycle_id, first_month_year, first_month_month, summary, revision_reason, supersedes_id, uploaded_by, created_at)"
                        + " values (?, ?, ?, 'CURRENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                id, cooperativeId, revisions.nextVersion(cooperativeId), name == null ? null : name.substring(0, Math.min(name.length(), 500)),
                hash, key, cycleId, first == null ? null : first.getYear(), first == null ? null : first.getMonthValue(), summary,
                current == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason),
                current == null ? null : current.id(), actor);
        auditService.record(
                actor,
                cooperativeId,
                current == null ? rw.terimbere.csams.shared.auditing.AuditableAction.IMPORT
                        : rw.terimbere.csams.shared.auditing.AuditableAction.IMPORT_REVISE,
                "OpeningWorkbookImport",
                id,
                current == null ? null : "{\"replaces\":\"" + current.id() + "\",\"version\":" + current.version() + "}",
                summary,
                http == null ? null : http.getRemoteAddr(),
                http == null ? null : http.getHeader("User-Agent"));
    }

    // ------------------------------------------------------------------ reading

    private static final DataFormatter FMT = new DataFormatter(Locale.ROOT);

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            } catch (IllegalStateException e) {
                return cell.getStringCellValue().trim();
            }
        }
        return FMT.formatCellValue(cell).trim();
    }

    private static Map<String, String> readSettings(Sheet sheet) {
        Map<String, String> map = new HashMap<>();
        if (sheet == null) {
            throw new ValidationException("The Scheme sheet is missing", Map.of("file", "Use the template"));
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String key = text(row.getCell(0));
            if (!key.isEmpty()) {
                map.put(key, text(row.getCell(1)));
            }
        }
        return map;
    }

    private static List<Map<String, String>> readRows(Sheet sheet, List<String> headers) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (sheet == null) {
            return rows;
        }
        Row head = sheet.getRow(0);
        for (int c = 0; c < headers.size(); c++) {
            if (head == null || !headers.get(c).equalsIgnoreCase(text(head.getCell(c)))) {
                throw new ValidationException("Sheet " + sheet.getSheetName() + " has a changed header",
                        Map.of("file", "Column " + (c + 1) + " must be \"" + headers.get(c) + "\""));
            }
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row.getCell(0)).isEmpty()) {
                continue;
            }
            Map<String, String> map = new HashMap<>();
            map.put("#", String.valueOf(r + 1));
            for (int c = 0; c < headers.size(); c++) {
                map.put(headers.get(c), text(row.getCell(c)));
            }
            rows.add(map);
        }
        return rows;
    }

    private static BigDecimal money(String value, String where, List<String> errors) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(value.replace(",", "").replace(" ", ""));
            if (v.signum() < 0) {
                errors.add(where + ": amounts cannot be negative");
                return BigDecimal.ZERO;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(where + ": \"" + value + "\" is not a number");
            return BigDecimal.ZERO;
        }
    }

    private static YearMonth month(String value, String where, List<String> errors) {
        try {
            String v = value == null ? "" : value.trim();
            return v.length() >= 10 ? YearMonth.from(LocalDate.parse(v.substring(0, 10))) : YearMonth.parse(v);
        } catch (DateTimeParseException e) {
            errors.add(where + ": \"" + value + "\" is not a month like 2026-01");
            return null;
        }
    }

    private static LocalDate date(String value, String where, List<String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim().substring(0, Math.min(10, value.trim().length())));
        } catch (DateTimeParseException e) {
            errors.add(where + ": \"" + value + "\" is not a date like 2026-02-10");
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
