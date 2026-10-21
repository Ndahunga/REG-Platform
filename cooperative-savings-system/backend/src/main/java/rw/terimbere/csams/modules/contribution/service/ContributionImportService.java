package rw.terimbere.csams.modules.contribution.service;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.dto.ContributionBatchRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionImportPreviewResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionImportPreviewRowResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionImportSummaryResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionLineRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionResponse;
import rw.terimbere.csams.modules.contribution.entity.ContributionImport;
import rw.terimbere.csams.modules.contribution.entity.ContributionImportRow;
import rw.terimbere.csams.modules.contribution.entity.ContributionImportStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionImportRepository;
import rw.terimbere.csams.modules.contribution.repository.ContributionImportRowRepository;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.export.ExcelReportExporter;
import rw.terimbere.csams.modules.report.export.ExcelReportWriter;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.file.FileStorageService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class ContributionImportService {

    public static final List<String> TEMPLATE_HEADERS = List.of(
            "Username", "Member Name", "Amount", "Payment Date", "Reference", "Notes");

    private static final DateTimeFormatter[] DATE_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final ContributionImportRepository importRepository;
    private final ContributionImportRowRepository importRowRepository;
    private final ContributionService contributionService;
    private final FileStorageService fileStorageService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public byte[] downloadTemplate(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return ExcelReportWriter.writeTemplate(
                "Contribution Import",
                TEMPLATE_HEADERS,
                List.of(List.of("member1", "Example Member", "1000.00", "2026-03-05", "REF-001", "Optional notes")));
    }

    @Transactional
    public ContributionImportPreviewResponse preview(
            UUID cooperativeId,
            int year,
            int month,
            MultipartFile file,
            HttpServletRequest httpRequest) {
        validatePeriod(year, month);
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        if (file == null || file.isEmpty()) {
            throw new ValidationException("Excel file is required");
        }
        String originalFilename = file.getOriginalFilename() == null ? "import.xlsx" : file.getOriginalFilename();
        if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ValidationException("Only .xlsx files are supported");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ValidationException("Unable to read uploaded file");
        }

        String storageKey = "contribution-imports/"
                + cooperativeId
                + "/"
                + UUID.randomUUID()
                + ".xlsx";
        fileStorageService.store(
                storageKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                ExcelReportExporter.CONTENT_TYPE);

        ContributionImport importEntity = ContributionImport.builder()
                .cooperativeId(cooperativeId)
                .year(year)
                .month(month)
                .originalFilename(originalFilename)
                .storageKey(storageKey)
                .contentType(ExcelReportExporter.CONTENT_TYPE)
                .sizeBytes((long) bytes.length)
                .status(ContributionImportStatus.UPLOADED)
                .uploadedBy(principal.getId())
                .build();
        importEntity = importRepository.save(importEntity);

        List<ParsedRow> parsed;
        try {
            parsed = parseWorkbook(bytes);
        } catch (Exception ex) {
            importEntity.setStatus(ContributionImportStatus.FAILED);
            importEntity.setErrorSummary("Failed to parse workbook: " + ex.getMessage());
            importRepository.save(importEntity);
            throw new ValidationException("Invalid Excel file: " + ex.getMessage());
        }

        List<ContributionImportRow> rows = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;
        for (ParsedRow parsedRow : parsed) {
            ValidatedRow validated = validateRow(cooperativeId, parsedRow);
            if (validated.valid()) {
                validCount++;
            } else {
                invalidCount++;
            }
            rows.add(ContributionImportRow.builder()
                    .importId(importEntity.getId())
                    .rowNumber(parsedRow.rowNumber())
                    .username(parsedRow.username())
                    .memberName(parsedRow.memberName())
                    .amount(validated.amount())
                    .paymentDate(validated.paymentDate())
                    .reference(trimToNull(parsedRow.reference()))
                    .notes(trimToNull(parsedRow.notes()))
                    .valid(validated.valid())
                    .errorMessages(validated.errors().isEmpty() ? null : String.join("; ", validated.errors()))
                    .memberUserId(validated.memberUserId())
                    .build());
        }
        importRowRepository.saveAll(rows);

        importEntity.setTotalRows(rows.size());
        importEntity.setValidRows(validCount);
        importEntity.setInvalidRows(invalidCount);
        importEntity.setStatus(ContributionImportStatus.VALIDATED);
        if (invalidCount > 0) {
            importEntity.setErrorSummary(invalidCount + " invalid row(s)");
        }
        importRepository.save(importEntity);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.IMPORT,
                "ContributionImport",
                importEntity.getId(),
                null,
                "{\"phase\":\"preview\",\"year\":"
                        + year
                        + ",\"month\":"
                        + month
                        + ",\"valid\":"
                        + validCount
                        + ",\"invalid\":"
                        + invalidCount
                        + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return toPreviewResponse(importEntity, rows);
    }

    @Transactional
    public List<ContributionResponse> confirm(
            UUID cooperativeId, UUID importId, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        ContributionImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("ContributionImport", importId));

        if (importEntity.getStatus() != ContributionImportStatus.VALIDATED
                && importEntity.getStatus() != ContributionImportStatus.UPLOADED) {
            throw new BusinessException("Import cannot be confirmed in status " + importEntity.getStatus());
        }

        List<ContributionImportRow> validRows =
                importRowRepository.findByImportIdAndValidTrueOrderByRowNumberAsc(importId);
        if (validRows.isEmpty()) {
            throw new ValidationException("No valid rows to import");
        }

        // Re-validate membership still ACTIVE before persist
        List<ContributionLineRequest> lines = new ArrayList<>();
        for (ContributionImportRow row : validRows) {
            ValidatedRow revalidated = validateRow(
                    cooperativeId,
                    new ParsedRow(
                            row.getRowNumber(),
                            row.getUsername(),
                            row.getMemberName(),
                            row.getAmount() == null ? "" : row.getAmount().toPlainString(),
                            row.getPaymentDate() == null ? "" : row.getPaymentDate().toString(),
                            row.getReference(),
                            row.getNotes()));
            if (!revalidated.valid() || revalidated.memberUserId() == null) {
                throw new BusinessException(
                        "Row " + row.getRowNumber() + " is no longer valid: " + String.join("; ", revalidated.errors()));
            }
            lines.add(ContributionLineRequest.builder()
                    .memberUserId(revalidated.memberUserId())
                    .paidAmount(revalidated.amount())
                    .paymentDate(revalidated.paymentDate())
                    .paymentReference(row.getReference())
                    .notes(row.getNotes())
                    .build());
        }

        List<ContributionResponse> saved = contributionService.batchSave(
                cooperativeId,
                importEntity.getYear(),
                importEntity.getMonth(),
                ContributionBatchRequest.builder().lines(lines).build(),
                httpRequest);

        importEntity.setStatus(ContributionImportStatus.CONFIRMED);
        importEntity.setConfirmedBy(principal.getId());
        importEntity.setConfirmedAt(Instant.now());
        importEntity.setValidRows(validRows.size());
        importRepository.save(importEntity);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.IMPORT,
                "ContributionImport",
                importEntity.getId(),
                null,
                "{\"phase\":\"confirm\",\"lines\":" + lines.size() + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return saved;
    }

    @Transactional
    public ContributionImportSummaryResponse cancel(UUID cooperativeId, UUID importId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        ContributionImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("ContributionImport", importId));

        if (importEntity.getStatus() == ContributionImportStatus.CONFIRMED) {
            throw new BusinessException("Confirmed imports cannot be cancelled");
        }
        if (importEntity.getStatus() == ContributionImportStatus.CANCELLED) {
            return toSummary(importEntity);
        }

        importEntity.setStatus(ContributionImportStatus.CANCELLED);
        importRepository.save(importEntity);
        return toSummary(importEntity);
    }

    @Transactional(readOnly = true)
    public ContributionImportPreviewResponse getImport(UUID cooperativeId, UUID importId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        ContributionImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("ContributionImport", importId));
        List<ContributionImportRow> rows = importRowRepository.findByImportIdOrderByRowNumberAsc(importId);
        return toPreviewResponse(importEntity, rows);
    }

    @Transactional(readOnly = true)
    public List<ContributionImportSummaryResponse> history(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return importRepository.findByCooperativeIdOrderByCreatedAtDesc(cooperativeId).stream()
                .map(this::toSummary)
                .toList();
    }

    private List<ParsedRow> parseWorkbook(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
                Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                throw new ValidationException("Workbook has no sheets");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<ParsedRow> rows = new ArrayList<>();
            int first = sheet.getFirstRowNum();
            int last = sheet.getLastRowNum();
            // Skip header row at first data row
            for (int i = first + 1; i <= last; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                String username = cellString(row.getCell(0), formatter);
                String memberName = cellString(row.getCell(1), formatter);
                String amount = cellString(row.getCell(2), formatter);
                String paymentDate = cellDateString(row.getCell(3), formatter);
                String reference = cellString(row.getCell(4), formatter);
                String notes = cellString(row.getCell(5), formatter);
                rows.add(new ParsedRow(i + 1, username, memberName, amount, paymentDate, reference, notes));
            }
            return rows;
        }
    }

    private ValidatedRow validateRow(UUID cooperativeId, ParsedRow row) {
        List<String> errors = new ArrayList<>();
        UUID memberUserId = null;
        BigDecimal amount = null;
        LocalDate paymentDate = null;

        if (!StringUtils.hasText(row.username())) {
            errors.add("Username is required");
        } else {
            Optional<User> userOpt =
                    userRepository.findByUsernameIgnoreCaseAndDeletedFalse(row.username().trim());
            if (userOpt.isEmpty()) {
                errors.add("Username not found");
            } else {
                User user = userOpt.get();
                Optional<CooperativeMembership> membership =
                        membershipRepository.findByCooperativeIdAndUserId(cooperativeId, user.getId());
                if (membership.isEmpty() || !"ACTIVE".equalsIgnoreCase(membership.get().getMembershipStatus())) {
                    errors.add("Username is not an ACTIVE member of this cooperative");
                } else {
                    memberUserId = user.getId();
                }
            }
        }

        if (!StringUtils.hasText(row.amountRaw())) {
            errors.add("Amount is required");
        } else {
            try {
                amount = MoneyUtils.scaleForStorage(new BigDecimal(row.amountRaw().trim().replace(",", "")));
                if (amount.compareTo(BigDecimal.ZERO) < 0) {
                    errors.add("Amount must be >= 0");
                    amount = null;
                }
            } catch (NumberFormatException | ArithmeticException ex) {
                errors.add("Amount is not a valid number");
            }
        }

        if (StringUtils.hasText(row.paymentDateRaw())) {
            paymentDate = parseDate(row.paymentDateRaw().trim());
            if (paymentDate == null) {
                errors.add("Payment date is not parseable");
            }
        }

        return new ValidatedRow(errors.isEmpty(), errors, memberUserId, amount, paymentDate);
    }

    private static LocalDate parseDate(String raw) {
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private static boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int c = 0; c <= 5; c++) {
            if (StringUtils.hasText(cellString(row.getCell(c), formatter))) {
                return false;
            }
        }
        return true;
    }

    private static String cellString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static String cellDateString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        try {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return cellString(cell, formatter);
    }

    private ContributionImportPreviewResponse toPreviewResponse(
            ContributionImport importEntity, List<ContributionImportRow> rows) {
        List<ContributionImportPreviewRowResponse> previewRows = rows.stream()
                .map(r -> ContributionImportPreviewRowResponse.builder()
                        .rowNumber(r.getRowNumber())
                        .username(r.getUsername())
                        .memberName(r.getMemberName())
                        .amount(r.getAmount() == null ? null : MoneyUtils.scale(r.getAmount()))
                        .paymentDate(r.getPaymentDate())
                        .reference(r.getReference())
                        .notes(r.getNotes())
                        .valid(r.isValid())
                        .errors(splitErrors(r.getErrorMessages()))
                        .memberUserId(r.getMemberUserId())
                        .build())
                .toList();
        return ContributionImportPreviewResponse.builder()
                .importId(importEntity.getId())
                .year(importEntity.getYear())
                .month(importEntity.getMonth())
                .status(importEntity.getStatus())
                .validCount(importEntity.getValidRows())
                .invalidCount(importEntity.getInvalidRows())
                .totalRows(importEntity.getTotalRows())
                .rows(previewRows)
                .build();
    }

    private ContributionImportSummaryResponse toSummary(ContributionImport entity) {
        return ContributionImportSummaryResponse.builder()
                .id(entity.getId())
                .cooperativeId(entity.getCooperativeId())
                .year(entity.getYear())
                .month(entity.getMonth())
                .originalFilename(entity.getOriginalFilename())
                .status(entity.getStatus())
                .totalRows(entity.getTotalRows())
                .validRows(entity.getValidRows())
                .invalidRows(entity.getInvalidRows())
                .uploadedBy(entity.getUploadedBy())
                .confirmedBy(entity.getConfirmedBy())
                .confirmedAt(entity.getConfirmedAt())
                .errorSummary(entity.getErrorSummary())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private static List<String> splitErrors(String errorMessages) {
        if (!StringUtils.hasText(errorMessages)) {
            return List.of();
        }
        return Arrays.stream(errorMessages.split(";"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private void requireCooperative(UUID cooperativeId) {
        cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private static void validatePeriod(int year, int month) {
        if (year < 2000 || year > 2100) {
            throw new ValidationException("year must be between 2000 and 2100");
        }
        if (month < 1 || month > 12) {
            throw new ValidationException("month must be between 1 and 12");
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    private record ParsedRow(
            int rowNumber,
            String username,
            String memberName,
            String amountRaw,
            String paymentDateRaw,
            String reference,
            String notes) {}

    private record ValidatedRow(
            boolean valid,
            List<String> errors,
            UUID memberUserId,
            BigDecimal amount,
            LocalDate paymentDate) {}
}
