package rw.terimbere.csams.modules.socialfund.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSettingsResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSettingsUpdateRequest;
import rw.terimbere.csams.modules.socialfund.entity.SocialFundSettings;
import rw.terimbere.csams.modules.socialfund.repository.SocialFundSettingsRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class SocialFundSettingsService {

    private final SocialFundSettingsRepository settingsRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional
    public SocialFundSettingsResponse getOrCreate(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        SocialFundSettings settings = settingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> createDefaults(cooperative));
        return toResponse(settings);
    }

    @Transactional
    public SocialFundSettingsResponse update(
            UUID cooperativeId, SocialFundSettingsUpdateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SocialFundSettings settings = settingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> createDefaults(cooperative));

        String previous = "{\"suggestedContributionAmount\":\""
                + settings.getSuggestedContributionAmount()
                + "\",\"enabled\":"
                + settings.isEnabled()
                + "}";

        settings.setSuggestedContributionAmount(
                MoneyUtils.scaleForStorage(request.getSuggestedContributionAmount()));
        settings.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        settings = settingsRepository.save(settings);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SETTINGS_CHANGE,
                "SocialFundSettings",
                settings.getId(),
                previous,
                "{\"suggestedContributionAmount\":\""
                        + settings.getSuggestedContributionAmount()
                        + "\",\"enabled\":"
                        + settings.isEnabled()
                        + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(settings);
    }

    private SocialFundSettings createDefaults(Cooperative cooperative) {
        SocialFundSettings settings = SocialFundSettings.builder()
                .cooperativeId(cooperative.getId())
                .suggestedContributionAmount(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .enabled(true)
                .build();
        return settingsRepository.save(settings);
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private SocialFundSettingsResponse toResponse(SocialFundSettings settings) {
        return SocialFundSettingsResponse.builder()
                .id(settings.getId())
                .cooperativeId(settings.getCooperativeId())
                .suggestedContributionAmount(MoneyUtils.scale(settings.getSuggestedContributionAmount()))
                .enabled(settings.isEnabled())
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
