package rw.terimbere.csams.modules.rotation.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Authorization, operating-model gate and calendar rules shared by the rotation services.
 *
 * <ul>
 *   <li>Read: any active member of the scheme (the same membership rule every other scheme API uses), or Super Admin.
 *   <li>Manage: Super Admin, or the scheme's President or Vice President, each with {@code COOPERATIVE_WRITE}.
 *   <li>Mutations apply only to ROTATING and HYBRID schemes; a legacy scheme with no recorded model must be classified
 *       first.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RotationAccess {

    static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final String WRITE_AUTHORITY = "COOPERATIVE_WRITE";

    private final CooperativeAuthorizationService authorizationService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final Clock clock;

    /** Membership of the scheme (404 for an unknown scheme). Returns the caller. */
    public UserPrincipal requireRead(UUID cooperativeId) {
        requireScheme(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return authorizationService.currentPrincipal();
    }

    /** Management rights on the scheme. Returns the caller. */
    public UserPrincipal requireManage(UUID cooperativeId) {
        requireScheme(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && principal.hasAuthority(WRITE_AUTHORITY)) {
            return principal;
        }
        authorizationService.requireMembership(cooperativeId);
        if (!CooperativeOfficerRoles.isLeadership(principal) || !principal.hasAuthority(WRITE_AUTHORITY)) {
            throw new ForbiddenException("President or Vice President with COOPERATIVE_WRITE required");
        }
        return principal;
    }

    /** Non-throwing management check for read endpoints that show officers more than members. */
    public boolean canManage(UserPrincipal principal, UUID cooperativeId) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return principal.hasAuthority(WRITE_AUTHORITY);
        }
        return principal.isMemberOf(cooperativeId)
                && CooperativeOfficerRoles.isLeadership(principal)
                && principal.hasAuthority(WRITE_AUTHORITY);
    }

    /** Rotation can be configured only for schemes that chose ROTATING or HYBRID. */
    public SchemeOperatingModel requireRotationModel(UUID cooperativeId) {
        SchemeOperatingModel model = cooperativeRepository.findOperatingModelById(cooperativeId);
        if (model == null) {
            throw new BusinessException(
                    "OPERATING_MODEL_NOT_CONFIGURED",
                    "The operating model of this saving scheme must be classified before rotation can be set up");
        }
        if (model == SchemeOperatingModel.ACCUMULATING) {
            throw new BusinessException(
                    "ROTATION_NOT_APPLICABLE", "Rotation applies only to Rotating and Hybrid saving schemes");
        }
        return model;
    }

    /** The user ids of the scheme's ACTIVE members. */
    public Set<UUID> activeMemberIds(UUID cooperativeId) {
        return membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE").stream()
                .map(CooperativeMembership::getUserId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Every id must be an active member of THIS scheme; otherwise a field error names the first offender. */
    public void requireActiveMembers(UUID cooperativeId, Collection<UUID> userIds, String field) {
        Set<UUID> active = activeMemberIds(cooperativeId);
        for (UUID id : userIds) {
            if (!active.contains(id)) {
                throw new ValidationException(
                        "Only active members of this saving scheme can take part in a rotation",
                        java.util.Map.of(field, "Not an active member of this saving scheme: " + id));
            }
        }
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(KIGALI));
    }

    /** A period has begun once its month has started; it can no longer be configured or reassigned. */
    public boolean hasBegun(YearMonth period) {
        return !period.isAfter(currentMonth());
    }

    private void requireScheme(UUID cooperativeId) {
        if (cooperativeId == null || cooperativeRepository.findByIdAndDeletedFalse(cooperativeId).isEmpty()) {
            throw new ResourceNotFoundException("Cooperative", cooperativeId);
        }
    }

    static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
