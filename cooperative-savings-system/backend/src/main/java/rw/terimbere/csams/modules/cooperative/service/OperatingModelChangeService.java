package rw.terimbere.csams.modules.cooperative.service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Changing the operating model of a running scheme (e.g. an accumulating scheme that starts rotating part of each
 * contribution). The change takes effect from a month that has not begun, so no month is ever split under two models;
 * it can be cancelled until then. When the month begins the scheme switches, and obligations of that month or later
 * that nobody has paid yet take the new model (paid ones keep the model they were paid under).
 */
@Service
@RequiredArgsConstructor
public class OperatingModelChangeService {

    private static final Logger log = LoggerFactory.getLogger(OperatingModelChangeService.class);
    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final CooperativeService cooperativeService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final SchemeFundLockService fundLockService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService obligationService;

    public record ChangeRequest(String operatingModel, Integer effectiveYear, Integer effectiveMonth, String reason) {}

    public record ChangeResponse(
            UUID id, String fromModel, String toModel, int effectiveYear, int effectiveMonth, String status, String reason,
            Instant createdAt, Instant appliedAt) {}

    @Transactional(readOnly = true)
    public List<ChangeResponse> list(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return jdbc.query(
                "select id, from_model, to_model, effective_year, effective_month, status, reason, created_at, applied_at"
                        + " from operating_model_changes where cooperative_id = ? order by created_at desc",
                (rs, i) -> new ChangeResponse(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()),
                cooperativeId);
    }

    @Transactional
    public ChangeResponse schedule(UUID cooperativeId, ChangeRequest request) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        cooperativeService.requireWriteAccess(principal, cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        SchemeOperatingModel current = cooperativeRepository.findOperatingModelById(cooperativeId);
        if (current == null) {
            throw new BusinessException("OPERATING_MODEL_NOT_SET", "Choose the scheme's operating model first");
        }
        SchemeOperatingModel next;
        try {
            next = SchemeOperatingModel.valueOf(String.valueOf(request.operatingModel()).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown operating model", Map.of("operatingModel", "ACCUMULATING, ROTATING or HYBRID"));
        }
        if (next == current) {
            throw new ValidationException("The scheme already uses this model", Map.of("operatingModel", "Choose a different model"));
        }
        if (request.effectiveYear() == null || request.effectiveMonth() == null) {
            throw new ValidationException("Choose the month the change takes effect", Map.of("effectiveMonth", "Required"));
        }
        YearMonth effective = YearMonth.of(request.effectiveYear(), request.effectiveMonth());
        if (!effective.isAfter(YearMonth.now(clock.withZone(KIGALI)))) {
            throw new BusinessException("MODEL_CHANGE_MONTH_STARTED", "A change can only take effect from a month that has not begun");
        }
        Long open = jdbc.queryForObject(
                "select count(*) from operating_model_changes where cooperative_id = ? and status = 'SCHEDULED'", Long.class, cooperativeId);
        if (open != null && open > 0) {
            throw new ConflictException("MODEL_CHANGE_PENDING", "A change is already scheduled; cancel it first");
        }
        UUID id = UUID.randomUUID();
        String reason = request.reason() == null ? null : request.reason().trim();
        jdbc.update("insert into operating_model_changes (id, cooperative_id, from_model, to_model, effective_year, effective_month,"
                        + " status, reason, requested_by) values (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?)",
                id, cooperativeId, current.name(), next.name(), effective.getYear(), effective.getMonthValue(), reason, principal.getId());
        auditService.record(principal.getId(), cooperativeId, AuditableAction.COOPERATIVE_OPERATING_MODEL_SET, "Cooperative",
                cooperativeId, "{\"operatingModel\":\"" + current + "\"}",
                "{\"scheduled\":\"" + next + "\",\"from\":\"" + effective + "\"}", null, null);
        return list(cooperativeId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void cancel(UUID cooperativeId, UUID changeId) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        cooperativeService.requireWriteAccess(principal, cooperativeId);
        int n = jdbc.update("update operating_model_changes set status = 'CANCELLED', cancelled_at = now()"
                + " where id = ? and cooperative_id = ? and status = 'SCHEDULED'", changeId, cooperativeId);
        if (n == 0) {
            throw new ResourceNotFoundException("Scheduled operating model change", changeId);
        }
    }

    /** Applies every scheduled change whose month has begun. Daily, and once at start-up. */
    @Scheduled(cron = "${app.operating-model.apply-cron:0 5 0 * * *}", zone = "Africa/Kigali")
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void applyDue() {
        YearMonth now;
        try {
            now = YearMonth.now(clock.withZone(KIGALI));
        } catch (RuntimeException e) { // a stubbed clock (tests) or none: use the system time
            now = YearMonth.now(KIGALI);
        }
        List<Map<String, Object>> due = jdbc.queryForList(
                "select id, cooperative_id, to_model, effective_year, effective_month from operating_model_changes"
                        + " where status = 'SCHEDULED' and (effective_year * 100 + effective_month) <= ?",
                now.getYear() * 100 + now.getMonthValue());
        for (Map<String, Object> row : due) {
            UUID id = (UUID) row.get("id");
            UUID coop = (UUID) row.get("cooperative_id");
            String to = (String) row.get("to_model");
            int key = ((Number) row.get("effective_year")).intValue() * 100 + ((Number) row.get("effective_month")).intValue();
            jdbc.update("update cooperatives set operating_model = ? where id = ?", to, coop);
            // Unpaid obligations of the new months take the new model; paid ones keep theirs.
            if ("ACCUMULATING".equals(to)) {
                jdbc.update("update contributions set operating_model_snapshot = ?, rotating_bps_snapshot = null,"
                                + " retained_bps_snapshot = null, rotating_unit_snapshot = null, rotation_period_id = null,"
                                + " rotation_configuration_id = null"
                                + " where cooperative_id = ? and (\"year\" * 100 + \"month\") >= ? and coalesce(paid_amount, 0) = 0",
                        to, coop, key);
            } else {
                jdbc.update("update contributions set operating_model_snapshot = ? where cooperative_id = ?"
                        + " and (\"year\" * 100 + \"month\") >= ? and coalesce(paid_amount, 0) = 0", to, coop, key);
            }
            jdbc.update("update operating_model_changes set status = 'APPLIED', applied_at = now() where id = ?", id);
            obligationService.evict(coop);
            log.info("Operating model of scheme {} changed to {}", coop, to);
        }
    }
}
