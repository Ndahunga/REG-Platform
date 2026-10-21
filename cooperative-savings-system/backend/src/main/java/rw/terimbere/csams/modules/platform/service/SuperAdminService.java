package rw.terimbere.csams.modules.platform.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.auth.repository.RefreshTokenRepository;
import rw.terimbere.csams.modules.platform.dto.SuperAdminCreateRequest;
import rw.terimbere.csams.modules.platform.dto.SuperAdminResponse;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * System (platform) administrator accounts: users holding the global {@code SUPER_ADMIN} role.
 *
 * <p>Distinct from Saving Scheme officers. These accounts have no cooperative membership and no
 * subscription. Lifecycle is deactivate/reactivate only; there is no delete.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminService.class);
    private static final String ROLE_CODE = CooperativeAuthorizationService.SUPER_ADMIN;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CooperativeAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public List<SuperAdminResponse> list() {
        authorizationService.requireSuperAdmin();
        UserPrincipal actor = authorizationService.currentPrincipal();
        return userRepository.findAllSuperAdmins().stream()
                .map(user -> toResponse(user, actor.getId()))
                .toList();
    }

    @Transactional
    public SuperAdminResponse create(SuperAdminCreateRequest request, HttpServletRequest httpRequest) {
        authorizationService.requireSuperAdmin();
        UserPrincipal actor = authorizationService.currentPrincipal();

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (username.length() < 3) {
            throw new ValidationException("Username must be at least 3 characters");
        }
        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(username)) {
            throw new ConflictException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(email)) {
            throw new ConflictException("Email already exists");
        }

        Role superAdmin = roleRepository
                .findByCode(ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role not seeded"));

        User user = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null)
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(new HashSet<>(Set.of(superAdmin)))
                .build());

        auditService.record(
                actor.getId(),
                null,
                AuditableAction.CREATE,
                "User",
                user.getId(),
                null,
                "{\"systemSuperAdmin\":true,\"username\":\"" + escape(username) + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        log.info("System super admin created username='{}' by={}", username, actor.getId());
        return toResponse(user, actor.getId());
    }

    /**
     * Deactivates or reactivates a system administrator.
     *
     * <p>The SUPER_ADMIN role row is locked first, which serializes every lifecycle change. That is what
     * stops two simultaneous deactivations from each seeing "another admin is still active" and together
     * leaving none.
     */
    @Transactional
    public SuperAdminResponse changeStatus(UUID targetId, AccountStatus requested, HttpServletRequest httpRequest) {
        authorizationService.requireSuperAdmin();
        if (requested != AccountStatus.ACTIVE && requested != AccountStatus.INACTIVE) {
            throw new ValidationException("status must be ACTIVE or INACTIVE");
        }
        UserPrincipal principal = authorizationService.currentPrincipal();

        roleRepository
                .findByCodeForUpdate(ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role not seeded"));

        // The token that got us here may be stale: re-check, under the lock, that the actor is still active.
        User actor = userRepository
                .findByIdAndDeletedFalse(principal.getId())
                .filter(u -> u.getAccountStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenException("Super admin access is required"));

        User target = userRepository
                .findByIdAndDeletedFalse(targetId)
                .filter(u -> userRepository.hasSuperAdminRole(u.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Super admin", targetId));

        AccountStatus previous = target.getAccountStatus();
        if (requested == AccountStatus.INACTIVE) {
            if (target.getId().equals(actor.getId())) {
                throw new ConflictException("You cannot deactivate your own account");
            }
            if (previous == AccountStatus.ACTIVE
                    && userRepository.countSuperAdminsWithStatus(AccountStatus.ACTIVE) <= 1) {
                throw new ConflictException("The last active super admin cannot be deactivated");
            }
        }
        if (previous == requested) {
            return toResponse(target, actor.getId());
        }

        target.setAccountStatus(requested);
        if (requested == AccountStatus.ACTIVE) {
            target.setFailedLoginAttempts(0);
            target.setLockedUntil(null);
        }
        userRepository.save(target);
        if (requested == AccountStatus.INACTIVE) {
            refreshTokenRepository.revokeAllByUserId(target.getId());
        }

        auditService.record(
                actor.getId(),
                null,
                AuditableAction.STATUS_CHANGE,
                "User",
                target.getId(),
                "{\"systemSuperAdmin\":true,\"accountStatus\":\"" + previous + "\"}",
                "{\"systemSuperAdmin\":true,\"accountStatus\":\"" + requested + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        log.info("System super admin {} -> {} target={} by={}", previous, requested, target.getId(), actor.getId());
        return toResponse(target, actor.getId());
    }

    private static SuperAdminResponse toResponse(User user, UUID currentUserId) {
        return SuperAdminResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .accountStatus(user.getAccountStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .currentUser(user.getId().equals(currentUserId))
                .build();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
