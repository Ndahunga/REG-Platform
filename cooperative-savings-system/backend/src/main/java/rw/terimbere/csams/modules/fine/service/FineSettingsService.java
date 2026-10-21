package rw.terimbere.csams.modules.fine.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.dto.FineSettingsResponse;
import rw.terimbere.csams.modules.fine.dto.FineSettingsUpdateRequest;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FineSettings;
import rw.terimbere.csams.modules.fine.repository.FineSettingsRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class FineSettingsService {

    private final FineSettingsRepository fineSettingsRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional
    public FineSettingsResponse getOrCreate(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        FineSettings settings = fineSettingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> createDefaults(cooperative));
        return toResponse(settings);
    }

    @Transactional
    public FineSettingsResponse update(
            UUID cooperativeId, FineSettingsUpdateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        CooperativeOfficerRoles.requireFineConfigurationManager(principal);

        FineSettings settings = fineSettingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> createDefaults(cooperative));

        String previous = "{\"fineMode\":\"" + settings.getFineMode() + "\",\"baseFineAmount\":\""
                + settings.getBaseFineAmount() + "\"}";

        if (request.getAutoFinesEnabled() != null) {
            settings.setAutoFinesEnabled(request.getAutoFinesEnabled());
        }
        settings.setFineMode(request.getFineMode() == null ? FineCalculationMode.FIXED : request.getFineMode());
        settings.setBaseFineAmount(MoneyUtils.scaleForStorage(request.getBaseFineAmount()));
        settings.setDailyIncrement(MoneyUtils.scaleForStorage(request.getDailyIncrement()));
        settings.setGraceDays(request.getGraceDays() == null ? 0 : request.getGraceDays());

        if (StringUtils.hasText(request.getCurrency())) {
            String currency = request.getCurrency().trim().toUpperCase(Locale.ROOT);
            if (currency.length() != 3) {
                throw new ValidationException("Currency must be a 3-letter ISO code");
            }
            settings.setCurrency(currency);
        } else {
            settings.setCurrency(cooperative.getCurrency());
        }

        settings = fineSettingsRepository.save(settings);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_SETTINGS_CHANGE,
                "FineSettings",
                settings.getId(),
                previous,
                "{\"fineMode\":\"" + settings.getFineMode() + "\",\"baseFineAmount\":\""
                        + settings.getBaseFineAmount() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(settings);
    }

    @Transactional
    public FineSettings requireSettings(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        return fineSettingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> createDefaults(cooperative));
    }

    private FineSettings createDefaults(Cooperative cooperative) {
        FineSettings settings = FineSettings.builder()
                .cooperativeId(cooperative.getId())
                .autoFinesEnabled(true)
                .fineMode(FineCalculationMode.FIXED)
                .baseFineAmount(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .dailyIncrement(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .graceDays(0)
                .currency(cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency())
                .build();
        return fineSettingsRepository.save(settings);
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private FineSettingsResponse toResponse(FineSettings settings) {
        return FineSettingsResponse.builder()
                .id(settings.getId())
                .cooperativeId(settings.getCooperativeId())
                .autoFinesEnabled(settings.isAutoFinesEnabled())
                .fineMode(settings.getFineMode())
                .baseFineAmount(MoneyUtils.scale(settings.getBaseFineAmount()))
                .dailyIncrement(MoneyUtils.scale(settings.getDailyIncrement()))
                .graceDays(settings.getGraceDays())
                .currency(settings.getCurrency())
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
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
