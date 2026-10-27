package rw.terimbere.csams.modules.historicalimport.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportRow;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet.Fields;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRowRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.erasure.AccountCleanup;
import rw.terimbere.csams.shared.erasure.DataEraser;
import rw.terimbere.csams.shared.erasure.DataGraph;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.file.FileStorageService;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Replaces a validated migration workbook with a corrected one. Runs inside the caller's transaction: the earlier
 * upload is undone, the corrected one is validated against the scheme as it would then be, and either everything is
 * written or nothing changes. The earlier upload stays on record, marked SUPERSEDED.
 *
 * <ul>
 *   <li><b>Members and share capital</b>: the earlier opening balances and share counts are taken back; corrected
 *       names, emails and phone numbers are applied to the accounts that upload created; rotation positions already
 *       imported for a member are carried over; a member left out of the corrected workbook is removed when nothing
 *       else was recorded for them.
 *   <li><b>Loans</b>: a loan identical in both versions stays exactly as it is, with anything recorded on it since;
 *       a loan removed or changed is replaced, unless something was already recorded on it (a repayment, say), in which
 *       case the revision is refused and says which loan.
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ImportRevisionService {

    private static final Set<String> LOAN_TABLES = Set.of("loans", "loan_installments");

    private final JdbcTemplate jdbc;
    private final DataEraser eraser;
    private final AccountCleanup accountCleanup;
    private final UserRepository userRepository;
    private final HistoricalImportRepository importRepository;
    private final HistoricalImportRowRepository importRowRepository;
    private final FileStorageService fileStorageService;
    private final MigrationWorkbookParser parser = new MigrationWorkbookParser();
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    record RotationFields(BigDecimal paid, BigDecimal received, BigDecimal owed) {
        boolean any() {
            return paid.signum() != 0 || received.signum() != 0 || owed.signum() != 0;
        }
    }

    record MembersReversal(Map<UUID, RotationFields> rotation, Set<UUID> previousMembers) {}

    record LoanRevision(int unchanged, int replaced, List<UUID> kept) {}

    /** The validated upload of this kind that a new upload would replace, if any. */
    HistoricalImport current(UUID cooperativeId, rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind kind) {
        return importRepository.findByCooperativeIdOrderByCreatedAtDesc(cooperativeId).stream()
                .filter(i -> i.getStatus() == HistoricalImportStatus.CONFIRMED && i.getImportKind() == kind && i.getCutoverDate() != null)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ members and share capital

    MembersReversal reverseMembers(UUID cooperativeId, HistoricalImport previous) {
        Map<UUID, RotationFields> rotation = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select member_user_id, opening_share_count, opening_rotation_paid, opening_rotation_received, opening_owed_to_member"
                        + " from member_opening_balances where cooperative_id = ? and historical_import_id = ?",
                cooperativeId, previous.getId());
        for (Map<String, Object> row : rows) {
            UUID member = (UUID) row.get("member_user_id");
            int shares = ((Number) row.get("opening_share_count")).intValue();
            if (shares != 0) {
                jdbc.update("update cooperative_memberships set share_count = case when coalesce(share_count, 0) > ?"
                                + " then coalesce(share_count, 0) - ? else 0 end where cooperative_id = ? and user_id = ?",
                        shares, shares, cooperativeId, member);
            }
            RotationFields fields = new RotationFields(
                    money(row.get("opening_rotation_paid")), money(row.get("opening_rotation_received")), money(row.get("opening_owed_to_member")));
            if (fields.any()) {
                rotation.put(member, fields);
            }
        }
        jdbc.update("delete from member_opening_balances where cooperative_id = ? and historical_import_id = ?", cooperativeId, previous.getId());
        Set<UUID> members = new LinkedHashSet<>();
        for (HistoricalImportRow row : importRowRepository.findByImportIdOrderBySheetAscRowNumberAsc(previous.getId())) {
            if (HistoricalMigrationSheet.MEMBER_REGISTRATION.getSheetName().equals(row.getSheet()) && row.getResultingEntityId() != null) {
                members.add(row.getResultingEntityId());
            }
        }
        rows.forEach(r -> members.add((UUID) r.get("member_user_id")));
        markSuperseded(previous);
        return new MembersReversal(rotation, members);
    }

    /**
     * Corrected details for accounts the earlier upload brought in (and that belong to this scheme only): the name,
     * email, phone and National ID in the corrected workbook replace the wrong ones, so the account is matched again.
     */
    void applyProfileCorrections(UUID cooperativeId, MigrationWorkbook workbook, Set<UUID> previousMembers) {
        for (MigrationWorkbook.Row row : workbook.rows(HistoricalMigrationSheet.MEMBER_REGISTRATION)) {
            String username = row.get(Fields.USERNAME);
            if (!StringUtils.hasText(username)) {
                continue;
            }
            User user = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username.trim()).orElse(null);
            if (user == null || !previousMembers.contains(user.getId()) || !onlyThisScheme(user.getId(), cooperativeId)
                    || user.getRoleCodes().contains("SUPER_ADMIN")) {
                continue;
            }
            boolean changed = false;
            MemberNameSplitter.Name name = MemberNameSplitter.split(row.get(Fields.FULL_NAME));
            if (StringUtils.hasText(name.firstName())
                    && (!name.firstName().equals(user.getFirstName()) || !name.lastName().equals(nz(user.getLastName())))) {
                user.setFirstName(name.firstName());
                user.setLastName(name.lastName());
                changed = true;
            }
            String email = row.get(Fields.EMAIL).trim();
            if (email.contains("@") && !email.equalsIgnoreCase(nz(user.getEmail()))
                    && userRepository.findByEmailIgnoreCaseAndDeletedFalse(email).isEmpty()) {
                user.setEmail(email);
                changed = true;
            }
            String rawPhone = row.get(Fields.PHONE);
            if (StringUtils.hasText(rawPhone)) {
                try {
                    String phone = CooperativeFieldRules.normalizePhone(rawPhone);
                    if (phone != null && !phone.equals(user.getPhone())) {
                        user.setPhone(phone);
                        changed = true;
                    }
                } catch (RuntimeException ignored) {
                    // an invalid phone is reported by the validation of the corrected workbook
                }
            }
            String nationalId = row.get(Fields.NATIONAL_ID).trim();
            if (nationalId.matches("^\\d{16}$") && !nationalId.equals(user.getNationalId())
                    && userRepository.findByNationalIdAndDeletedFalse(nationalId).isEmpty()) {
                user.setNationalId(nationalId);
                changed = true;
            }
            if (changed) {
                userRepository.saveAndFlush(user);
            }
        }
    }

    /** Rotation positions imported for a member before the correction are carried to the member's new opening balance. */
    void restoreRotation(UUID cooperativeId, MembersReversal reversal) {
        entityManager.flush(); // the corrected opening balances are written before they are read with SQL
        List<String> lost = new ArrayList<>();
        reversal.rotation().forEach((member, f) -> {
            int n = jdbc.update("update member_opening_balances set opening_rotation_paid = ?, opening_rotation_received = ?,"
                            + " opening_owed_to_member = ? where cooperative_id = ? and member_user_id = ?",
                    f.paid(), f.received(), f.owed(), cooperativeId, member);
            if (n == 0) {
                lost.add(userRepository.findById(member).map(User::getUsername).orElse(member.toString()));
            }
        });
        if (!lost.isEmpty()) {
            throw new BusinessException("ROTATION_POSITIONS_WOULD_BE_LOST",
                    "These members have rotation positions from the rotation workbook and are missing from the corrected"
                            + " workbook: " + String.join(", ", lost) + ". Keep them in it, or revise the rotation workbook first.");
        }
    }

    /**
     * Members of the earlier upload left out of the corrected one: removed (with their account, if it serves no other
     * scheme) when nothing else was recorded for them; otherwise kept, and named so an officer can decide.
     */
    List<String> removeDroppedMembers(UUID cooperativeId, Set<UUID> previousMembers, Set<UUID> nowMembers) {
        entityManager.flush();
        List<String> kept = new ArrayList<>();
        DataGraph g = eraser.graph();
        for (UUID member : previousMembers) {
            if (nowMembers.contains(member)) {
                continue;
            }
            boolean hasRecords = false;
            for (DataGraph.Ref ref : g.refsTo("users")) {
                if (Set.of("cooperative_memberships", "notifications", "audit_logs").contains(ref.child())
                        || !g.has(ref.child(), "cooperative_id")) {
                    continue;
                }
                Long n = jdbc.queryForObject("select count(*) from " + ref.child() + " where " + ref.column() + " = ? and cooperative_id = ?",
                        Long.class, member, cooperativeId);
                if (n != null && n > 0) {
                    hasRecords = true;
                    break;
                }
            }
            if (hasRecords) {
                kept.add(userRepository.findById(member).map(User::getUsername).orElse(member.toString()));
                continue;
            }
            jdbc.update("delete from notifications where user_id = ? and cooperative_id = ?", member, cooperativeId);
            jdbc.update("delete from cooperative_memberships where cooperative_id = ? and user_id = ?", cooperativeId, member);
            accountCleanup.deleteIfUnused(member);
        }
        return kept;
    }

    Set<UUID> membersOf(UUID cooperativeId, UUID importId) {
        entityManager.flush();
        return new HashSet<>(jdbc.queryForList(
                "select member_user_id from member_opening_balances where cooperative_id = ? and historical_import_id = ?",
                UUID.class, cooperativeId, importId));
    }

    // ------------------------------------------------------------------ loans

    /**
     * Matches the corrected loan rows with the earlier ones. Identical rows keep their loan (and are taken out of the
     * workbook so they are not imported twice); the earlier loans with no identical row are deleted, unless something was
     * recorded on them since.
     */
    LoanRevision reviseLoans(UUID cooperativeId, HistoricalImport previous, MigrationWorkbook corrected, LocalDate newCutover) {
        Map<Integer, UUID> loanByRow = new HashMap<>();
        for (HistoricalImportRow row : importRowRepository.findByImportIdOrderBySheetAscRowNumberAsc(previous.getId())) {
            if (HistoricalMigrationSheet.LOAN_REGISTRATION.getSheetName().equals(row.getSheet()) && row.getResultingEntityId() != null) {
                loanByRow.put(row.getRowNumber(), row.getResultingEntityId());
            }
        }
        Set<UUID> existing = new HashSet<>(jdbc.queryForList(
                "select id from loans where cooperative_id = ? and opening_import_id = ?", UUID.class, cooperativeId, previous.getId()));
        Map<String, Deque<UUID>> bySignature = new HashMap<>();
        if (newCutover.equals(previous.getCutoverDate())) {
            MigrationWorkbook old = parser.parse(read(previous.getStorageKey()));
            for (MigrationWorkbook.Row row : old.rows(HistoricalMigrationSheet.LOAN_REGISTRATION)) {
                UUID loan = loanByRow.get(row.rowNumber());
                if (loan != null && existing.contains(loan)) {
                    bySignature.computeIfAbsent(signature(row), k -> new ArrayDeque<>()).add(loan);
                }
            }
        }
        List<UUID> kept = new ArrayList<>();
        List<MigrationWorkbook.Row> remaining = new ArrayList<>();
        for (MigrationWorkbook.Row row : corrected.rows(HistoricalMigrationSheet.LOAN_REGISTRATION)) {
            Deque<UUID> match = bySignature.get(signature(row));
            if (match != null && !match.isEmpty()) {
                kept.add(match.poll());
            } else {
                remaining.add(row);
            }
        }
        corrected.sheets().put(HistoricalMigrationSheet.LOAN_REGISTRATION, remaining);
        Set<UUID> replaced = new LinkedHashSet<>(existing);
        kept.forEach(replaced::remove);
        if (!replaced.isEmpty()) {
            DataEraser.Plan plan = eraser.plan(Map.of("loans", replaced));
            Set<String> touched = new LinkedHashSet<>(plan.counts().keySet());
            touched.removeAll(LOAN_TABLES);
            if (!touched.isEmpty()) {
                List<String> names = jdbc.queryForList(
                        "select distinct u.username from loans l join users u on u.id = l.member_user_id where l.id in ("
                                + String.join(",", java.util.Collections.nCopies(replaced.size(), "?")) + ")",
                        String.class, replaced.toArray());
                throw new BusinessException("LOAN_HAS_ACTIVITY",
                        "A loan this correction removes or changes already has records since the import (" + String.join(", ", touched)
                                + "), for: " + String.join(", ", names)
                                + ". Keep that loan's row exactly as it was, or reverse what was recorded on it first.");
            }
            eraser.execute(plan);
        }
        markSuperseded(previous);
        return new LoanRevision(kept.size(), replaced.size(), kept);
    }

    /** The loans kept from the earlier version now belong to the upload that replaced it. */
    void moveKeptLoans(List<UUID> kept, UUID newImportId) {
        entityManager.flush();
        for (UUID loan : kept) {
            jdbc.update("update loans set opening_import_id = ? where id = ?", newImportId, loan);
        }
    }

    private static String signature(MigrationWorkbook.Row row) {
        StringBuilder sb = new StringBuilder();
        for (String field : HistoricalMigrationSheet.LOAN_REGISTRATION.getHeaders()) {
            sb.append(normalize(row.get(field))).append('|');
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(v.replace(",", "").replace(" ", "")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // not a number
        }
        if (v.length() >= 10) {
            try {
                return LocalDate.parse(v.substring(0, 10)).toString();
            } catch (RuntimeException ignored) {
                // not a date
            }
        }
        return v.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ shared

    void markSuperseded(HistoricalImport previous) {
        previous.setStatus(HistoricalImportStatus.SUPERSEDED);
        previous.setSupersededAt(Instant.now());
        importRepository.saveAndFlush(previous);
    }

    private boolean onlyThisScheme(UUID userId, UUID cooperativeId) {
        Long others = jdbc.queryForObject("select count(*) from cooperative_memberships where user_id = ? and cooperative_id <> ?",
                Long.class, userId, cooperativeId);
        return others == null || others == 0;
    }

    private byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(fileStorageService.load(storageKey));
        } catch (IOException ex) {
            throw ValidationException.coded("FILE_UNREADABLE", "Unable to read the earlier workbook");
        }
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
