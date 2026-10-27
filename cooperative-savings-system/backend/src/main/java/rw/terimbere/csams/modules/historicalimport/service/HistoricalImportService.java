package rw.terimbere.csams.modules.historicalimport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportConfirmResponse;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportError;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportPreviewResponse;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportSheetSummary;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportSummaryResponse;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalMigrationSummary;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalReconciliationSummary;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportRow;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.historicalimport.strategy.HistoricalMigrationGuard;
import rw.terimbere.csams.modules.historicalimport.strategy.MigrationStrategy;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRowRepository;
import rw.terimbere.csams.modules.report.export.ExcelReportExporter;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.file.FileStorageService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Ikimina historical migration (opening balances at a cutover date).
 *
 * <p>The workbook captures the Saving Scheme's <em>position</em> at the end of the cutover date: member
 * contribution totals, share count and share capital, and loans still outstanding. It never recreates
 * old monthly transactions. Imports confirmed in the retired 16-sheet format remain readable through
 * {@link #get} and {@link #history} but can no longer be created.
 */
@Service
@RequiredArgsConstructor
public class HistoricalImportService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final LocalDate MIN_CUTOVER = LocalDate.of(1950, 1, 1);
    private static final TypeReference<List<HistoricalImportError>> ERROR_LIST = new TypeReference<>() {};

    private final CooperativeRepository cooperativeRepository;
    private final HistoricalImportRepository importRepository;
    private final HistoricalImportRowRepository importRowRepository;
    private final MigrationWorkbookParser parser = new MigrationWorkbookParser();
    private final MigrationValidator validator;
    private final MigrationPersistenceService persistenceService;
    private final FileStorageService fileStorageService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final HistoricalMigrationGuard migrationGuard;
    private final ImportRevisionService revisions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** What undoing the earlier upload did, so the corrected one can be completed. */
    private record RevisionOutcome(
            ImportRevisionService.MembersReversal members, ImportRevisionService.LoanRevision loans) {}

    private record Simulation(ValidatedMigration validated, RevisionOutcome outcome) {}

    @Transactional(readOnly = true)
    public byte[] downloadTemplate(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        requireLeadership(cooperativeId);
        return MigrationTemplateWriter.write();
    }

    /** The separate template for loans still outstanding at the cutover (one sheet, Loan Registration). */
    @Transactional(readOnly = true)
    public byte[] downloadLoanTemplate(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        requireLeadership(cooperativeId);
        return MigrationTemplateWriter.writeLoans();
    }

    @Transactional
    public HistoricalImportPreviewResponse preview(
            UUID cooperativeId,
            MultipartFile file,
            LocalDate cutoverDate,
            BigDecimal openingAvailableFunds,
            HttpServletRequest request) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = requireLeadership(cooperativeId);
        requireValidCutover(cutoverDate);
        byte[] bytes = readXlsx(file);
        String hash = HistoricalFingerprint.sha256Hex(bytes);
        if (importRepository.existsByCooperativeIdAndFileHashAndStatus(
                cooperativeId, hash, HistoricalImportStatus.CONFIRMED)) {
            throw new BusinessException("IMPORT_FILE_ALREADY_CONFIRMED", "This workbook has already been confirmed for this Saving Scheme");
        }
        // Parse first: an old-format workbook is rejected before anything is stored.
        MigrationWorkbook parsed = parser.parse(bytes);
        // The loan workbook carries no cash position (that belongs to the opening balances), so it has none to validate.
        boolean loansOnly = parsed.kind() == HistoricalImportKind.OPENING_LOANS;
        BigDecimal funds = loansOnly ? null : requireValidOpeningFunds(openingAvailableFunds);

        // A validated upload of the same workbook already exists: this one is a correction of it.
        HistoricalImport previous = revisions.current(cooperativeId, parsed.kind());

        String originalFilename = file.getOriginalFilename() == null ? "historical-import.xlsx" : file.getOriginalFilename();
        String storageKey = "historical-imports/" + cooperativeId + "/" + UUID.randomUUID() + ".xlsx";
        fileStorageService.store(
                storageKey, new ByteArrayInputStream(bytes), bytes.length, ExcelReportExporter.CONTENT_TYPE);

        HistoricalImport importEntity = importRepository.save(HistoricalImport.builder()
                .cooperativeId(cooperativeId)
                .originalFilename(originalFilename)
                .storageKey(storageKey)
                .contentType(ExcelReportExporter.CONTENT_TYPE)
                .sizeBytes((long) bytes.length)
                .fileHash(hash)
                .status(HistoricalImportStatus.UPLOADED)
                .importKind(parsed.kind())
                .cutoverDate(cutoverDate)
                .openingAvailableFunds(funds)
                .uploadedBy(principal.getId())
                .revisionOfImportId(previous == null ? null : previous.getId())
                .build());

        ValidatedMigration validated;
        RevisionOutcome outcome = null;
        if (previous == null) {
            validated = validator.validate(cooperative.getId(), parsed, cutoverDate, funds);
        } else {
            // Validated as the scheme would be with the earlier upload undone; nothing of that is kept.
            Simulation simulation = simulateRevision(cooperative.getId(), previous.getId(), bytes, cutoverDate, funds);
            validated = simulation.validated();
            outcome = simulation.outcome();
        }
        persistPreviewRows(importEntity.getId(), validated);
        importEntity.setTotalRows(validated.totalRows());
        importEntity.setValidRows(validated.validRows());
        importEntity.setInvalidRows(validated.invalidRows());
        importEntity.setValidatedAt(Instant.now());
        importEntity.setSheetSummary(toJson(sheetSummaries(validated.summary)));
        importEntity.setMigrationSummary(toJson(validated.summary));
        boolean blocked = validated.hasErrors();
        importEntity.setStatus(blocked ? HistoricalImportStatus.INVALID : HistoricalImportStatus.READY);
        if (blocked) {
            importEntity.setErrorSummary(validated.errors.size() + " error(s) found");
        }
        importRepository.save(importEntity);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.IMPORT,
                "HistoricalImport",
                importEntity.getId(),
                null,
                "{\"phase\":\"preview\",\"format\":\"" + parsed.kind() + "\",\"filename\":\""
                        + escape(originalFilename)
                        + "\",\"hash\":\""
                        + hash
                        + "\",\"cutoverDate\":\""
                        + cutoverDate
                        + "\",\"valid\":"
                        + validated.validRows()
                        + ",\"invalid\":"
                        + validated.invalidRows()
                        + "}",
                clientIp(request),
                userAgent(request));
        return HistoricalImportPreviewResponse.builder()
                .importId(importEntity.getId())
                .status(importEntity.getStatus())
                .format(HistoricalImportPreviewResponse.FORMAT_OPENING_BALANCE)
                .kind(parsed.kind().name())
                .originalFilename(importEntity.getOriginalFilename())
                .fileHash(importEntity.getFileHash())
                .cutoverDate(cutoverDate)
                .totalRows(validated.totalRows())
                .validRows(validated.validRows())
                .invalidRows(validated.invalidRows())
                .confirmAllowed(confirmAllowed(importEntity))
                .reportReady(confirmAllowed(importEntity))
                .migration(validated.summary)
                .sheets(sheetSummaries(validated.summary))
                .errors(validated.errors)
                .errorSummary(importEntity.getErrorSummary())
                .revisionOfImportId(previous == null ? null : previous.getId())
                .revisionOfFilename(previous == null ? null : previous.getOriginalFilename())
                .revisionOfConfirmedAt(previous == null ? null : previous.getConfirmedAt())
                .unchangedLoans(outcome == null || outcome.loans() == null ? null : outcome.loans().unchanged())
                .replacedLoans(outcome == null || outcome.loans() == null ? null : outcome.loans().replaced())
                .build();
    }

    private Simulation simulateRevision(UUID cooperativeId, UUID previousId, byte[] bytes, LocalDate cutoverDate, BigDecimal funds) {
        TransactionTemplate simulation = new TransactionTemplate(transactionManager);
        simulation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return simulation.execute(status -> {
            status.setRollbackOnly();
            HistoricalImport previous = importRepository.findByIdAndCooperativeId(previousId, cooperativeId).orElseThrow();
            MigrationWorkbook parsed = parser.parse(bytes);
            RevisionOutcome outcome = undo(cooperativeId, previous, parsed, cutoverDate);
            return new Simulation(validator.validate(cooperativeId, parsed, cutoverDate, funds), outcome);
        });
    }

    /** Undoes the earlier upload so the corrected workbook (adjusted here when needed) can take its place. */
    private RevisionOutcome undo(UUID cooperativeId, HistoricalImport previous, MigrationWorkbook corrected, LocalDate cutoverDate) {
        if (previous.getImportKind() == HistoricalImportKind.OPENING_LOANS) {
            return new RevisionOutcome(null, revisions.reviseLoans(cooperativeId, previous, corrected, cutoverDate));
        }
        ImportRevisionService.MembersReversal members = revisions.reverseMembers(cooperativeId, previous);
        revisions.applyProfileCorrections(cooperativeId, corrected, members.previousMembers());
        return new RevisionOutcome(members, null);
    }

    public HistoricalImportConfirmResponse confirm(UUID cooperativeId, UUID importId, HttpServletRequest request) {
        return confirm(cooperativeId, importId, null, request);
    }

    /** Validates (confirms) an upload. A correction of an earlier validated upload needs the reason for it. */
    public HistoricalImportConfirmResponse confirm(UUID cooperativeId, UUID importId, String reason, HttpServletRequest request) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = requireLeadership(cooperativeId);
        // Refused before anything is touched (and so before the import could be marked failed) when this scheme
        // migrates the other way: bringing the same history in twice would count it twice.
        HistoricalImport candidate = importRepository.findByIdAndCooperativeId(importId, cooperativeId).orElse(null);
        boolean revision = candidate != null && candidate.getRevisionOfImportId() != null;
        if (revision && (reason == null || reason.trim().length() < 10)) {
            throw new BusinessException("REVISION_REASON_REQUIRED", "Explain what this corrected upload fixes (at least 10 characters)");
        }
        if (candidate != null && candidate.getStatus() == HistoricalImportStatus.READY && !revision) {
            migrationGuard.requireStrategy(
                    cooperativeId, MigrationStrategy.OPENING_BALANCE, candidate.getCutoverDate(), principal.getId());
            if (candidate.getImportKind() == HistoricalImportKind.OPENING_LOANS
                    && !importRepository.existsByCooperativeIdAndImportKindAndStatus(
                            cooperativeId, HistoricalImportKind.OPENING_BALANCE, HistoricalImportStatus.CONFIRMED)) {
                throw new BusinessException(
                        "OPENING_BALANCES_FIRST",
                        "Import the opening balances (members and share capital) first. The loans are added on top of them.");
            }
        }
        TransactionTemplate business = new TransactionTemplate(transactionManager);
        try {
            String cleanReason = reason == null ? null : reason.trim();
            return business.execute(status -> doConfirm(cooperative, importId, principal, cleanReason, request));
        } catch (RuntimeException ex) {
            TransactionTemplate markFailed = new TransactionTemplate(transactionManager);
            markFailed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            markFailed.executeWithoutResult(s -> importRepository
                    .findByIdAndCooperativeId(importId, cooperativeId)
                    .ifPresent(entity -> {
                        if (entity.getStatus() != HistoricalImportStatus.CONFIRMED) {
                            entity.setStatus(HistoricalImportStatus.FAILED);
                            entity.setErrorSummary(ex.getMessage());
                            importRepository.save(entity);
                        }
                    }));
            throw ex;
        }
    }

    private HistoricalImportConfirmResponse doConfirm(
            Cooperative cooperative, UUID importId, UserPrincipal principal, String reason, HttpServletRequest request) {
        HistoricalImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperative.getId())
                .orElseThrow(() -> new ResourceNotFoundException("HistoricalImport", importId));
        if (importEntity.getStatus() == HistoricalImportStatus.CONFIRMED) {
            throw new BusinessException("IMPORT_ALREADY_CONFIRMED", "This historical import has already been confirmed");
        }
        if (importEntity.getCutoverDate() == null
                || (importEntity.getImportKind() != HistoricalImportKind.OPENING_LOANS
                        && importEntity.getOpeningAvailableFunds() == null)) {
            throw ValidationException.coded("OLD_TEMPLATE_NOT_SUPPORTED", MigrationWorkbookParser.UNSUPPORTED_FORMAT);
        }
        if (importEntity.getStatus() != HistoricalImportStatus.READY) {
            throw new BusinessException("IMPORT_NOT_CONFIRMABLE", "Import cannot be confirmed in status " + importEntity.getStatus());
        }
        if (importRepository.existsByCooperativeIdAndFileHashAndStatus(
                cooperative.getId(), importEntity.getFileHash(), HistoricalImportStatus.CONFIRMED)) {
            throw new BusinessException("IMPORT_FILE_ALREADY_CONFIRMED", "This workbook has already been confirmed for this Saving Scheme");
        }

        HistoricalImport previous = null;
        if (importEntity.getRevisionOfImportId() != null) {
            previous = importRepository.findByIdAndCooperativeId(importEntity.getRevisionOfImportId(), cooperative.getId()).orElse(null);
            if (previous == null || previous.getStatus() != HistoricalImportStatus.CONFIRMED) {
                throw new BusinessException("REVISION_OUTDATED",
                        "The upload this corrects was already replaced. Upload the corrected workbook again.");
            }
            if (previous.getImportKind() == HistoricalImportKind.OPENING_BALANCE
                    && !importEntity.getCutoverDate().equals(previous.getCutoverDate())) {
                // The correction moves the cutover date: the scheme's opening balances are now as at the new date.
                jdbc.update("update cooperative_migration_strategies set cutover_date = ? where cooperative_id = ? and strategy = 'OPENING_BALANCE'",
                        importEntity.getCutoverDate(), cooperative.getId());
            }
            migrationGuard.requireStrategy(
                    cooperative.getId(), MigrationStrategy.OPENING_BALANCE, importEntity.getCutoverDate(), principal.getId());
        }

        // Re-validate against the data as it is now: balances or identities may have changed since preview.
        MigrationWorkbook parsed = parser.parse(readStored(importEntity.getStorageKey()));
        RevisionOutcome outcome = previous == null ? null : undo(cooperative.getId(), previous, parsed, importEntity.getCutoverDate());
        ValidatedMigration validated =
                validator.validate(
                        cooperative.getId(),
                        parsed,
                        importEntity.getCutoverDate(),
                        importEntity.getOpeningAvailableFunds());
        if (validated.hasErrors() || validated.invalidRows() > 0) {
            throw ValidationException.coded("IMPORT_NO_LONGER_VALID", "Workbook is no longer valid. Fix the errors and upload again.");
        }
        boolean onlyKeptLoans = outcome != null && outcome.loans() != null && outcome.loans().unchanged() > 0;
        if (validated.validRows() <= 0 && !onlyKeptLoans) {
            throw ValidationException.coded("IMPORT_NO_ROWS", "Workbook has no importable rows");
        }

        MigrationPersistenceService.PersistResult persisted =
                persistenceService.persist(cooperative, validated, importId, principal.getId());
        List<HistoricalImportRow> rows = importRowRepository.findByImportIdOrderBySheetAscRowNumberAsc(importId);
        for (HistoricalImportRow row : rows) {
            UUID entityId = persisted.resultingEntityIds().get(MigrationPersistenceService.key(row.getSheet(), row.getRowNumber()));
            if (entityId != null) {
                row.setResultingEntityId(entityId);
                row.setResultingEntityType(row.getSheet());
            }
        }
        importRowRepository.saveAll(rows);

        List<String> keptMembers = List.of();
        if (outcome != null && outcome.members() != null) {
            revisions.restoreRotation(cooperative.getId(), outcome.members());
            keptMembers = revisions.removeDroppedMembers(
                    cooperative.getId(), outcome.members().previousMembers(), revisions.membersOf(cooperative.getId(), importId));
        }
        if (outcome != null && outcome.loans() != null) {
            revisions.moveKeptLoans(outcome.loans().kept(), importId);
        }
        if (previous != null) {
            previous.setSupersededByImportId(importId);
            importRepository.save(previous);
            importEntity.setRevisionReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        }

        HistoricalImportConfirmResponse response = persisted.counts();
        response.setImportId(importEntity.getId());
        response.setStatus(HistoricalImportStatus.CONFIRMED);
        validated.summary.setResult(response);

        importEntity.setStatus(HistoricalImportStatus.CONFIRMED);
        importEntity.setConfirmedBy(principal.getId());
        importEntity.setConfirmedAt(Instant.now());
        importEntity.setMigrationSummary(toJson(validated.summary));
        importRepository.save(importEntity);

        Map<String, Object> payload = confirmAuditPayload(importEntity, response);
        if (previous != null) {
            payload.put("phase", "revise");
            payload.put("replaces", String.valueOf(previous.getId()));
            payload.put("replacedFilename", previous.getOriginalFilename());
            if (outcome.loans() != null) {
                payload.put("loansKept", outcome.loans().unchanged());
                payload.put("loansReplaced", outcome.loans().replaced());
            }
            if (!keptMembers.isEmpty()) {
                payload.put("membersLeftOutButKept", keptMembers);
            }
        }
        auditService.record(
                principal.getId(),
                cooperative.getId(),
                previous == null ? AuditableAction.IMPORT : AuditableAction.IMPORT_REVISE,
                "HistoricalImport",
                importEntity.getId(),
                previous == null ? null : toJson(Map.of("importId", String.valueOf(previous.getId()), "filename",
                        String.valueOf(previous.getOriginalFilename()))),
                toJson(payload),
                clientIp(request),
                userAgent(request));
        response.setMembersLeftOutButKept(keptMembers);
        return response;
    }

    @Transactional
    public HistoricalImportSummaryResponse cancel(UUID cooperativeId, UUID importId, HttpServletRequest request) {
        UserPrincipal principal = requireLeadership(cooperativeId);
        HistoricalImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("HistoricalImport", importId));
        if (importEntity.getStatus() == HistoricalImportStatus.CONFIRMED) {
            throw new BusinessException("IMPORT_CANNOT_CANCEL_CONFIRMED", "Confirmed imports cannot be cancelled");
        }
        if (importEntity.getStatus() == HistoricalImportStatus.CANCELLED) {
            return toSummary(importEntity);
        }
        importEntity.setStatus(HistoricalImportStatus.CANCELLED);
        importEntity.setCancelledBy(principal.getId());
        importEntity.setCancelledAt(Instant.now());
        importRepository.save(importEntity);
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.IMPORT,
                "HistoricalImport",
                importEntity.getId(),
                null,
                "{\"phase\":\"cancel\"}",
                clientIp(request),
                userAgent(request));
        return toSummary(importEntity);
    }

    /** Reads any import, including ones confirmed in the retired 16-sheet format. */
    @Transactional(readOnly = true)
    public HistoricalImportPreviewResponse get(UUID cooperativeId, UUID importId) {
        requireLeadership(cooperativeId);
        HistoricalImport importEntity = importRepository
                .findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("HistoricalImport", importId));
        List<HistoricalImportRow> rows = importRowRepository.findByImportIdOrderBySheetAscRowNumberAsc(importId);
        boolean openingBalance = isOpeningBalance(importEntity);
        List<HistoricalImportError> errors = new ArrayList<>();
        for (HistoricalImportRow row : rows) {
            if (row.isValid() || !StringUtils.hasText(row.getErrorMessages())) {
                continue;
            }
            if (openingBalance) {
                List<HistoricalImportError> stored = readJson(row.getErrorMessages(), ERROR_LIST);
                if (stored != null) {
                    errors.addAll(stored);
                    continue;
                }
            }
            errors.add(HistoricalImportError.builder()
                    .sheet(row.getSheet())
                    .rowNumber(row.getRowNumber())
                    .message(row.getErrorMessages())
                    .build());
        }
        boolean ready = confirmAllowed(importEntity);
        HistoricalImportPreviewResponse.HistoricalImportPreviewResponseBuilder builder =
                HistoricalImportPreviewResponse.builder()
                        .importId(importEntity.getId())
                        .status(importEntity.getStatus())
                        .originalFilename(importEntity.getOriginalFilename())
                        .fileHash(importEntity.getFileHash())
                        .cutoverDate(importEntity.getCutoverDate())
                        .totalRows(importEntity.getTotalRows())
                        .validRows(importEntity.getValidRows())
                        .invalidRows(importEntity.getInvalidRows())
                        .confirmAllowed(ready)
                        .reportReady(ready)
                        .sheets(readJson(importEntity.getSheetSummary(), new TypeReference<List<HistoricalImportSheetSummary>>() {}))
                        .errors(errors)
                        .errorSummary(importEntity.getErrorSummary());
        if (openingBalance) {
            builder.kind(importEntity.getImportKind().name())
                    .format(HistoricalImportPreviewResponse.FORMAT_OPENING_BALANCE)
                    .migration(readJson(importEntity.getMigrationSummary(), new TypeReference<HistoricalMigrationSummary>() {}));
        } else {
            HistoricalReconciliationSummary reconciliation = readJson(
                    importEntity.getReconciliationSummary(),
                    new TypeReference<HistoricalReconciliationSummary>() {});
            builder.format(HistoricalImportPreviewResponse.FORMAT_LEGACY)
                    .yearSummaries(reconciliation == null || reconciliation.getYearSummaries() == null
                            ? List.of()
                            : reconciliation.getYearSummaries())
                    .reconciliation(reconciliation);
        }
        return builder.build();
    }

    @Transactional(readOnly = true)
    public List<HistoricalImportSummaryResponse> history(UUID cooperativeId) {
        requireLeadership(cooperativeId);
        return importRepository.findByCooperativeIdOrderByCreatedAtDesc(cooperativeId).stream()
                .map(this::toSummary)
                .toList();
    }

    private void persistPreviewRows(UUID importId, ValidatedMigration validated) {
        importRowRepository.deleteByImportId(importId);
        List<HistoricalImportRow> rows = new ArrayList<>();
        // Workbook-level problems (missing sheet, wrong header) are kept as header-row entries so they can be re-read later.
        validated.errors.stream()
                .filter(e -> e.getRowNumber() == null || e.getRowNumber() <= 1 && "Header".equals(e.getField()))
                .forEach(e -> rows.add(HistoricalImportRow.builder()
                        .importId(importId)
                        .sheet(e.getSheet() == null ? "Workbook" : e.getSheet())
                        .rowNumber(e.getRowNumber() == null ? 1 : e.getRowNumber())
                        .valid(false)
                        .errorMessages(toJson(List.of(e)))
                        .build()));
        for (ValidatedMigration.ValidatedRow<?> row : validated.allRows()) {
            rows.add(HistoricalImportRow.builder()
                    .importId(importId)
                    .sheet(row.sheet().getSheetName())
                    .rowNumber(row.rowNumber())
                    .sourceKey(row.sourceKey() == null ? null : HistoricalFingerprint.normalize(row.sourceKey()))
                    .fingerprint(row.fingerprint())
                    .valid(row.valid())
                    .errorMessages(row.errors() == null || row.errors().isEmpty() ? null : toJson(row.errors()))
                    .build());
        }
        importRowRepository.saveAll(rows);
    }

    private static List<HistoricalImportSheetSummary> sheetSummaries(HistoricalMigrationSummary summary) {
        List<HistoricalImportSheetSummary> sheets = new ArrayList<>();
        sheets.add(new HistoricalImportSheetSummary(
                "Member Registration",
                summary.getMembers().getTotalRows(),
                summary.getMembers().getValidRows(),
                summary.getMembers().getInvalidRows()));
        sheets.add(new HistoricalImportSheetSummary(
                "Share Capital Registration",
                summary.getShareCapital().getTotalRows(),
                summary.getShareCapital().getValidRows(),
                summary.getShareCapital().getInvalidRows()));
        sheets.add(new HistoricalImportSheetSummary(
                "Loan Registration",
                summary.getLoans().getTotalRows(),
                summary.getLoans().getValidRows(),
                summary.getLoans().getInvalidRows()));
        return sheets;
    }

    private HistoricalImportSummaryResponse toSummary(HistoricalImport entity) {
        return HistoricalImportSummaryResponse.builder()
                .id(entity.getId())
                .cooperativeId(entity.getCooperativeId())
                .originalFilename(entity.getOriginalFilename())
                .fileHash(entity.getFileHash())
                .status(entity.getStatus())
                .format(isOpeningBalance(entity)
                        ? HistoricalImportPreviewResponse.FORMAT_OPENING_BALANCE
                        : HistoricalImportPreviewResponse.FORMAT_LEGACY)
                .kind(entity.getImportKind().name())
                .cutoverDate(entity.getCutoverDate())
                .totalRows(entity.getTotalRows())
                .validRows(entity.getValidRows())
                .invalidRows(entity.getInvalidRows())
                .uploadedBy(entity.getUploadedBy())
                .createdAt(entity.getCreatedAt())
                .confirmedAt(entity.getConfirmedAt())
                .cancelledAt(entity.getCancelledAt())
                .errorSummary(entity.getErrorSummary())
                .confirmedBy(entity.getConfirmedBy())
                .revisionOfImportId(entity.getRevisionOfImportId())
                .supersededByImportId(entity.getSupersededByImportId())
                .supersededAt(entity.getSupersededAt())
                .revisionReason(entity.getRevisionReason())
                .build();
    }

    private static boolean isOpeningBalance(HistoricalImport entity) {
        return entity.getCutoverDate() != null;
    }

    private static BigDecimal requireValidOpeningFunds(BigDecimal value) {
        if (value == null) {
            throw ValidationException.coded("OPENING_FUNDS_REQUIRED", "Available Group Funds at Cutover (RWF) is required");
        }
        if (value.signum() < 0) {
            throw ValidationException.coded("OPENING_FUNDS_NEGATIVE", "Available Group Funds at Cutover (RWF) cannot be negative");
        }
        BigDecimal scaled = MoneyUtils.scale(value);
        if (scaled.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw ValidationException.coded("OPENING_FUNDS_TOO_LARGE", "Available Group Funds at Cutover (RWF) is too large");
        }
        return scaled;
    }

    private static void requireValidCutover(LocalDate cutoverDate) {
        if (cutoverDate == null) {
            throw ValidationException.coded("CUTOVER_REQUIRED", "Historical data through (cutover date) is required");
        }
        if (cutoverDate.isBefore(MIN_CUTOVER)) {
            throw ValidationException.coded("CUTOVER_UNREALISTIC", "Historical data through (cutover date) is not a realistic date");
        }
        if (cutoverDate.isAfter(LocalDate.now(CooperativeFieldRules.ZONE))) {
            throw ValidationException.coded("CUTOVER_IN_FUTURE", "Historical data through (cutover date) cannot be in the future");
        }
    }

    private byte[] readXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ValidationException.coded("FILE_REQUIRED", "Excel file is required");
        }
        String name = file.getOriginalFilename() == null ? "import.xlsx" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw ValidationException.coded("FILE_NOT_XLSX", "Only .xlsx files are supported");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ValidationException.coded("FILE_TOO_LARGE", "Excel file must be 10MB or smaller");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw ValidationException.coded("FILE_UNREADABLE", "Unable to read uploaded file");
        }
    }

    private byte[] readStored(String storageKey) {
        try {
            return Files.readAllBytes(fileStorageService.load(storageKey));
        } catch (IOException ex) {
            throw ValidationException.coded("FILE_UNREADABLE", "Unable to read stored workbook");
        }
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private UserPrincipal requireLeadership(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || CooperativeOfficerRoles.isLeadership(principal)) {
            return principal;
        }
        throw new ForbiddenException("Only cooperative leadership can import historical data");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static Map<String, Object> confirmAuditPayload(
            HistoricalImport importEntity, HistoricalImportConfirmResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "confirm");
        payload.put("format", "OPENING_BALANCE");
        payload.put("filename", importEntity.getOriginalFilename());
        payload.put("cutoverDate", String.valueOf(importEntity.getCutoverDate()));
        payload.put("members", response.getMembersImported());
        payload.put("memberOpeningBalances", response.getMemberOpeningBalancesImported());
        payload.put("shareOpeningBalances", response.getShareOpeningBalancesImported());
        payload.put("loans", response.getLoansImported());
        payload.put("loanInstallments", response.getLoanInstallmentsCreated());
        payload.put("totalHistoricalContributions", response.getTotalHistoricalContributions());
        payload.put("totalHistoricalShareCapital", response.getTotalHistoricalShareCapital());
        payload.put("totalOutstandingLoanPrincipal", response.getTotalOutstandingLoanPrincipal());
        payload.put("status", "CONFIRMED");
        return payload;
    }

    private static boolean confirmAllowed(HistoricalImport entity) {
        return entity.getStatus() == HistoricalImportStatus.READY
                && entity.getValidRows() > 0
                && entity.getInvalidRows() == 0;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'");
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
}
