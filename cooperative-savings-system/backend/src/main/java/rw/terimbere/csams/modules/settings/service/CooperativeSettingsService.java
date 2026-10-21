package rw.terimbere.csams.modules.settings.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.settings.dto.CooperativeSettingsResponse;
import rw.terimbere.csams.modules.settings.dto.CooperativeSettingsUpdateRequest;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;
import rw.terimbere.csams.modules.settings.repository.CooperativeSettingsRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Service
@RequiredArgsConstructor
public class CooperativeSettingsService {

    private final CooperativeSettingsRepository settingsRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;

    @Transactional
    public CooperativeSettingsResponse getOrCreate(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return toResponse(findOrCreate(cooperativeId));
    }

    @Transactional
    public CooperativeSettingsResponse update(UUID cooperativeId, CooperativeSettingsUpdateRequest request) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        CooperativeSettings settings = findOrCreate(cooperativeId);
        if (StringUtils.hasText(request.getTimezone())) {
            settings.setTimezone(request.getTimezone().trim());
        } else {
            throw new ValidationException("timezone is required");
        }
        if (StringUtils.hasText(request.getLocale())) {
            settings.setLocale(request.getLocale().trim());
        } else {
            throw new ValidationException("locale is required");
        }
        if (request.getNotifyContributions() != null) {
            settings.setNotifyContributions(request.getNotifyContributions());
        }
        if (request.getNotifyLoans() != null) {
            settings.setNotifyLoans(request.getNotifyLoans());
        }
        if (request.getNotifyFines() != null) {
            settings.setNotifyFines(request.getNotifyFines());
        }
        if (request.getNotifyPayouts() != null) {
            settings.setNotifyPayouts(request.getNotifyPayouts());
        }
        if (Boolean.TRUE.equals(request.getClearBaseSharePrice())) {
            settings.setBaseSharePrice(null);
        } else if (request.getBaseSharePrice() != null) {
            settings.setBaseSharePrice(request.getBaseSharePrice());
        }
        return toResponse(settingsRepository.save(settings));
    }

    private CooperativeSettings findOrCreate(UUID cooperativeId) {
        return settingsRepository
                .findByCooperativeId(cooperativeId)
                .orElseGet(() -> settingsRepository.save(CooperativeSettings.builder()
                        .cooperativeId(cooperativeId)
                        .timezone("Africa/Kigali")
                        .locale("en")
                        .notifyContributions(true)
                        .notifyLoans(true)
                        .notifyFines(true)
                        .notifyPayouts(true)
                        .build()));
    }

    private void requireCooperative(UUID cooperativeId) {
        if (!cooperativeRepository.existsById(cooperativeId)) {
            throw new ResourceNotFoundException("Cooperative not found");
        }
    }

    private CooperativeSettingsResponse toResponse(CooperativeSettings s) {
        return CooperativeSettingsResponse.builder()
                .id(s.getId())
                .cooperativeId(s.getCooperativeId())
                .timezone(s.getTimezone())
                .locale(s.getLocale())
                .notifyContributions(s.isNotifyContributions())
                .notifyLoans(s.isNotifyLoans())
                .notifyFines(s.isNotifyFines())
                .notifyPayouts(s.isNotifyPayouts())
                .baseSharePrice(s.getBaseSharePrice())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .version(s.getVersion())
                .build();
    }
}
