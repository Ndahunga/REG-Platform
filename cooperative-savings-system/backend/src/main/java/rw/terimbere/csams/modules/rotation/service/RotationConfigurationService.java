package rw.terimbere.csams.modules.rotation.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ConfigurationRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ConfigurationResponse;
import rw.terimbere.csams.modules.rotation.entity.RotationConfiguration;
import rw.terimbere.csams.modules.rotation.repository.RotationConfigurationRepository;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Effective-dated rotating/retained split. Storage only: nothing here allocates or posts any money.
 *
 * <p>Rules: ROTATING stores 10000/0; HYBRID needs both portions positive and a 10000 total. A configuration may only
 * take effect from a month that has not begun, rows are never updated, and the configuration for a period is the
 * latest one effective on or before it. A configuration can be deleted only while it has not taken effect and no
 * contribution was split under it; after that it is history and a newer configuration replaces it.
 */
@Service
@RequiredArgsConstructor
public class RotationConfigurationService {

    private final RotationAccess access;
    private final RotationConfigurationRepository repository;
    private final SchemeFundLockService fundLockService;
    private final AuditService auditService;
    private final Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public ConfigurationResponse create(UUID cooperativeId, ConfigurationRequest request, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        SchemeOperatingModel model = access.requireRotationModel(cooperativeId);
        validateSplit(model, request.rotatingBps(), request.retainedBps());

        YearMonth effective = YearMonth.of(request.effectiveYear(), request.effectiveMonth());
        if (access.hasBegun(effective)) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED",
                    "A configuration can only take effect from a month that has not begun yet");
        }

        // Serializes concurrent changes of this scheme; the unique key below is the backstop.
        fundLockService.lockSchemeFund(cooperativeId);
        Optional<RotationConfiguration> existing = repository.findByCooperativeIdAndEffectiveYearAndEffectiveMonth(
                cooperativeId, effective.getYear(), effective.getMonthValue());
        if (existing.isPresent()) {
            RotationConfiguration found = existing.get();
            if (found.getRotatingBps() == request.rotatingBps() && found.getRetainedBps() == request.retainedBps()) {
                return toResponse(found); // an identical retry is a success, not a second row
            }
            throw new ConflictException(
                    "ROTATION_CONFIG_EXISTS",
                    "A different configuration already takes effect from that month and cannot be changed");
        }

        RotationConfiguration saved = repository.saveAndFlush(RotationConfiguration.builder()
                .cooperativeId(cooperativeId)
                .effectiveYear(effective.getYear())
                .effectiveMonth(effective.getMonthValue())
                .rotatingBps(request.rotatingBps())
                .retainedBps(request.retainedBps())
                .createdBy(principal.getId())
                .createdAt(Instant.now(clock))
                .build());
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROTATION_CONFIG_CREATE,
                "RotationConfiguration",
                saved.getId(),
                null,
                "{\"effective\":\"" + effective + "\",\"rotatingBps\":" + saved.getRotatingBps()
                        + ",\"retainedBps\":" + saved.getRetainedBps() + "}",
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ConfigurationResponse> history(UUID cooperativeId) {
        access.requireRead(cooperativeId);
        return repository.findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(cooperativeId).stream()
                .map(c -> toResponse(c, blockedReason(c)))
                .toList();
    }

    /**
     * Deletes a configuration that has not taken effect and that nothing was built on. One whose month has begun, or
     * under which contributions were already split, is part of the history of that money and can never be removed;
     * correct the future by adding a newer configuration instead.
     */
    @Transactional
    public void delete(UUID cooperativeId, UUID configurationId, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        RotationConfiguration config = repository
                .findById(configurationId)
                .filter(c -> cooperativeId.equals(c.getCooperativeId()))
                .orElseThrow(() -> new rw.terimbere.csams.shared.exceptions.ResourceNotFoundException("Rotation configuration", configurationId));
        String blocked = blockedReason(config);
        if (blocked != null) {
            throw new ConflictException(
                    blocked,
                    "ROTATION_CONFIG_IN_FORCE".equals(blocked)
                            ? "This configuration has already taken effect and cannot be deleted. Add a newer one instead."
                            : "Contributions were already split under this configuration and it cannot be deleted. Add a newer one instead.");
        }
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROTATION_CONFIG_DELETE,
                "RotationConfiguration",
                config.getId(),
                "{\"effective\":\"" + YearMonth.of(config.getEffectiveYear(), config.getEffectiveMonth()) + "\",\"rotatingBps\":"
                        + config.getRotatingBps() + ",\"retainedBps\":" + config.getRetainedBps() + "}",
                null,
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
        repository.delete(config);
    }

    private String blockedReason(RotationConfiguration c) {
        YearMonth effective = YearMonth.of(c.getEffectiveYear(), c.getEffectiveMonth());
        if (access.hasBegun(effective)) {
            return "ROTATION_CONFIG_IN_FORCE";
        }
        Long used = jdbc.queryForObject(
                "select count(*) from contributions where cooperative_id = ? and rotating_bps_snapshot is not null"
                        + " and (\"year\" * 100 + \"month\") >= ?",
                Long.class, c.getCooperativeId(), effective.getYear() * 100 + effective.getMonthValue());
        return used != null && used > 0 ? "ROTATION_CONFIG_IN_USE" : null;
    }

    /** The configuration governing a period, or empty when none is effective yet. */
    @Transactional(readOnly = true)
    public Optional<ConfigurationResponse> effective(UUID cooperativeId, int year, int month) {
        access.requireRead(cooperativeId);
        return resolve(cooperativeId, year, month).map(RotationConfigurationService::toResponse);
    }

    /** Effective-dated resolution, for the services and the later obligation work. No authorization, no lock. */
    @Transactional(readOnly = true)
    public Optional<RotationConfiguration> resolve(UUID cooperativeId, int year, int month) {
        return repository.findEffectiveFor(cooperativeId, year, month, PageRequest.of(0, 1)).stream()
                .findFirst();
    }

    private static void validateSplit(SchemeOperatingModel model, int rotatingBps, int retainedBps) {
        if (rotatingBps + retainedBps != 10000) {
            throw new ValidationException(
                    "Rotating and retained portions must total 100%",
                    Map.of("retainedBps", "Rotating and retained portions must total 10000 basis points"));
        }
        if (model == SchemeOperatingModel.ROTATING && (rotatingBps != 10000 || retainedBps != 0)) {
            throw new ValidationException(
                    "A Rotating saving scheme distributes 100% of contributions",
                    Map.of("rotatingBps", "Must be 10000 for a Rotating saving scheme"));
        }
        if (model == SchemeOperatingModel.HYBRID && (rotatingBps <= 0 || retainedBps <= 0)) {
            throw new ValidationException(
                    "A Hybrid saving scheme needs a positive retained and a positive rotating portion",
                    Map.of("rotatingBps", "Both portions must be greater than zero for a Hybrid saving scheme"));
        }
    }

    static ConfigurationResponse toResponse(RotationConfiguration c) {
        return toResponse(c, null);
    }

    static ConfigurationResponse toResponse(RotationConfiguration c, String deletionBlockedReason) {
        return new ConfigurationResponse(
                c.getId(),
                c.getEffectiveYear(),
                c.getEffectiveMonth(),
                c.getRotatingBps(),
                c.getRetainedBps(),
                c.getCreatedBy(),
                c.getCreatedAt(),
                deletionBlockedReason == null,
                deletionBlockedReason);
    }
}
