package rw.terimbere.csams.security;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Permission;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Single source of truth for cooperative-scoped authorization.
 *
 * <p>The caller's role in a cooperative is read from
 * {@code cooperative_memberships.role_in_cooperative} on every call (never from the JWT), so role and
 * status changes take effect immediately. The {@code roles}/{@code role_permissions} tables are used only
 * as the mapping from role code to permission codes.
 */
@Service
@RequiredArgsConstructor
public class CooperativeAccessResolver {

    private static final long ROLE_PERMISSION_CACHE_TTL_MS = 60_000L;

    private final CooperativeMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;

    private final Map<String, CachedPermissions> rolePermissionCache = new ConcurrentHashMap<>();

    /** What a user may do inside one cooperative. Non-members and inactive members get empty sets. */
    public record CooperativeAccess(
            UUID cooperativeId,
            boolean activeMember,
            String membershipRole,
            Set<String> roles,
            Set<String> permissions) {}

    private record CachedPermissions(Set<String> permissions, long loadedAtMs) {}

    @Transactional(readOnly = true)
    public CooperativeAccess resolve(UUID userId, UUID cooperativeId) {
        if (userId == null || cooperativeId == null) {
            return denied(cooperativeId);
        }
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, userId)
                .orElse(null);
        if (membership == null || !"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            return denied(cooperativeId);
        }

        String role = effectiveRole(membership.getRoleInCooperative());
        Set<String> roles = rolesFor(role);
        return new CooperativeAccess(cooperativeId, true, role, roles, permissionsFor(roles));
    }

    /**
     * Permissions granted by a membership's {@code role_in_cooperative}. Callers must already have
     * established that the membership is ACTIVE. Unknown or blank roles fail closed to plain MEMBER.
     */
    public Set<String> permissionsForMembershipRole(String roleInCooperative) {
        return permissionsFor(rolesFor(effectiveRole(roleInCooperative)));
    }

    private Set<String> rolesFor(String effectiveRole) {
        Set<String> roles = new HashSet<>();
        roles.add(CooperativeOfficerRoles.MEMBER);
        String platformRole = CooperativeOfficerRoles.platformRole(effectiveRole);
        if (platformRole != null) {
            roles.add(platformRole);
        }
        return Set.copyOf(roles);
    }

    private Set<String> permissionsFor(Set<String> roles) {
        Set<String> permissions = new HashSet<>();
        for (String roleCode : roles) {
            permissions.addAll(permissionsOfRole(roleCode));
        }
        return Set.copyOf(permissions);
    }

    /**
     * Returns a copy of {@code principal} whose roles, permissions and cooperative list reflect only its
     * membership in {@code cooperativeId}. Super Admins keep their global access and are returned as-is.
     */
    public UserPrincipal scope(UserPrincipal principal, UUID cooperativeId) {
        if (principal == null
                || cooperativeId == null
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return principal;
        }
        if (cooperativeId.equals(principal.getScopedCooperativeId())) {
            return principal;
        }
        CooperativeAccess access = resolve(principal.getId(), cooperativeId);

        Set<UUID> cooperativeIds = new HashSet<>(
                principal.getCooperativeIds() == null ? Set.of() : principal.getCooperativeIds());
        if (access.activeMember()) {
            cooperativeIds.add(cooperativeId);
        } else {
            cooperativeIds.remove(cooperativeId);
        }

        return principal.toBuilder()
                .roles(access.roles())
                .permissions(access.permissions())
                .cooperativeIds(cooperativeIds)
                .scopedCooperativeId(cooperativeId)
                .build();
    }

    /** Unknown or blank stored roles fail closed to plain MEMBER. */
    private static String effectiveRole(String roleInCooperative) {
        try {
            return CooperativeOfficerRoles.normalize(roleInCooperative);
        } catch (ValidationException ex) {
            return CooperativeOfficerRoles.MEMBER;
        }
    }

    private Set<String> permissionsOfRole(String roleCode) {
        long now = System.currentTimeMillis();
        CachedPermissions cached = rolePermissionCache.get(roleCode);
        if (cached != null && now - cached.loadedAtMs() < ROLE_PERMISSION_CACHE_TTL_MS) {
            return cached.permissions();
        }
        Set<String> loaded = roleRepository
                .findByCode(roleCode)
                .map(role -> role.getPermissions().stream()
                        .map(Permission::getCode)
                        .collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
        rolePermissionCache.put(roleCode, new CachedPermissions(loaded, now));
        return loaded;
    }

    private static CooperativeAccess denied(UUID cooperativeId) {
        return new CooperativeAccess(cooperativeId, false, null, Set.of(), Set.of());
    }
}
