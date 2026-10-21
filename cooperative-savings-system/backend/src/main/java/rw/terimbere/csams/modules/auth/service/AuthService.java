package rw.terimbere.csams.modules.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.configuration.AppProperties;
import rw.terimbere.csams.configuration.JwtProperties;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.auth.dto.AuthResult;
import rw.terimbere.csams.modules.auth.dto.AuthUserResponse;
import rw.terimbere.csams.modules.auth.dto.BootstrapAdminRequest;
import rw.terimbere.csams.modules.auth.dto.ChangePasswordRequest;
import rw.terimbere.csams.modules.auth.dto.LoginRequest;
import rw.terimbere.csams.modules.auth.dto.LoginResponse;
import rw.terimbere.csams.modules.auth.dto.PasswordResetConfirmRequest;
import rw.terimbere.csams.modules.auth.dto.PasswordResetRequest;
import rw.terimbere.csams.modules.auth.dto.SignupRequest;
import rw.terimbere.csams.modules.auth.dto.UpdateProfileRequest;
import rw.terimbere.csams.modules.auth.entity.PasswordResetToken;
import rw.terimbere.csams.modules.auth.entity.RefreshToken;
import rw.terimbere.csams.modules.auth.repository.PasswordResetTokenRepository;
import rw.terimbere.csams.modules.auth.repository.RefreshTokenRepository;
import rw.terimbere.csams.modules.auth.util.TokenHashing;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.account.AccountNotificationCopy;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CustomUserDetailsService;
import rw.terimbere.csams.security.JwtService;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.UnauthorizedException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String GENERIC_RESET_MESSAGE =
            "If an account exists for that username or email, a password reset link has been sent.";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final Environment environment;

    /**
     * Creates the first SUPER_ADMIN when the users table is empty. Rejects once any user exists.
     */
    @Transactional
    public AuthUserResponse bootstrapAdmin(BootstrapAdminRequest request, HttpServletRequest httpRequest) {
        // Lock the role row so two simultaneous bootstraps cannot both see an empty users table.
        Role superAdmin = roleRepository
                .findByCodeForUpdate("SUPER_ADMIN")
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role not seeded"));
        if (userRepository.countByDeletedFalse() > 0) {
            throw new ConflictException("Bootstrap is only allowed when no users exist");
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        String nationalId = StringUtils.hasText(request.getNationalId()) ? request.getNationalId().trim() : null;

        User admin = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null)
                .nationalId(nationalId)
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(new HashSet<>(Set.of(superAdmin)))
                .build();

        admin = userRepository.save(admin);

        auditService.record(
                admin.getId(),
                null,
                AuditableAction.CREATE,
                "User",
                admin.getId(),
                null,
                "{\"bootstrap\":true,\"username\":\"" + username + "\"}",
                clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));

        log.info("Bootstrapped first SUPER_ADMIN username='{}'", username);
        return currentUser(admin.getId());
    }

    /**
     * Legacy public self-registration. Always creates a plain MEMBER account, never a SUPER_ADMIN,
     * whatever the number of existing users. System administrators come only from
     * {@code POST /api/v1/auth/bootstrap} (empty installation) or the Super Admin portal.
     */
    @Transactional
    public AuthResult signup(SignupRequest request, HttpServletRequest httpRequest) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(username)) {
            throw new ConflictException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(email)) {
            throw new ConflictException("Email already exists");
        }

        String roleCode = "MEMBER";
        Role role = roleRepository
                .findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException(roleCode + " role not seeded"));

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null)
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(new HashSet<>(Set.of(role)))
                .build();

        user = userRepository.save(user);

        String ip = clientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        auditService.record(
                user.getId(),
                null,
                AuditableAction.CREATE,
                "User",
                user.getId(),
                null,
                "{\"signup\":true,\"role\":\"" + roleCode + "\",\"username\":\"" + username + "\"}",
                ip,
                userAgent);

        log.info("Signed up user='{}' role={}", username, roleCode);
        notificationFacade.notifyUser(
                user.getId(),
                null,
                NotificationType.ACCOUNT,
                AccountNotificationCopy.WELCOME_TITLE,
                AccountNotificationCopy.welcomeSignupBody(),
                "User",
                user.getId());
        return issueTokens(user, ip, userAgent);
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResult login(LoginRequest request, HttpServletRequest httpRequest) {
        Instant now = Instant.now();
        String ip = clientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);

        User user = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse(request.getUsername())
                .orElse(null);

        if (user == null) {
            auditService.record(
                    null,
                    null,
                    AuditableAction.LOGIN_FAILURE,
                    "User",
                    null,
                    null,
                    null,
                    ip,
                    userAgent);
            throw new UnauthorizedException("Invalid username or password");
        }

        if (user.isEffectivelyLocked(now)) {
            auditService.record(
                    user.getId(),
                    null,
                    AuditableAction.LOGIN_FAILURE,
                    "User",
                    user.getId(),
                    null,
                    "{\"reason\":\"locked\"}",
                    ip,
                    userAgent);
            throw new UnauthorizedException("Account temporarily locked");
        }

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            auditService.record(
                    user.getId(),
                    null,
                    AuditableAction.LOGIN_FAILURE,
                    "User",
                    user.getId(),
                    null,
                    "{\"reason\":\"inactive\"}",
                    ip,
                    userAgent);
            throw new UnauthorizedException("Account is not active");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(user, now, ip, userAgent);
            throw new UnauthorizedException("Invalid username or password");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (user.getAccountStatus() == AccountStatus.LOCKED) {
            user.setAccountStatus(AccountStatus.ACTIVE);
        }
        user.setLastLoginAt(now);
        userRepository.save(user);

        AuthResult result = issueTokens(user, ip, userAgent);
        auditService.record(
                user.getId(),
                null,
                AuditableAction.LOGIN_SUCCESS,
                "User",
                user.getId(),
                null,
                null,
                ip,
                userAgent);
        return result;
    }

    @Transactional
    public AuthResult refresh(
            Optional<String> bodyToken, HttpServletRequest request, HttpServletResponse response) {
        String rawRefresh = resolveRefreshToken(bodyToken, request);
        if (!StringUtils.hasText(rawRefresh) || !jwtService.validateRefreshToken(rawRefresh)) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        String hash = TokenHashing.sha256Hex(rawRefresh);
        RefreshToken stored = refreshTokenRepository
                .findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        Instant now = Instant.now();
        if (stored.getExpiresAt().isBefore(now)) {
            stored.setRevoked(true);
            refreshTokenRepository.save(stored);
            throw new UnauthorizedException("Refresh token expired");
        }

        UUID userId = jwtService.extractUserIdFromRefreshToken(rawRefresh);
        User user = userRepository
                .findWithRolesById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        if (user.getAccountStatus() != AccountStatus.ACTIVE || user.isDeleted()) {
            throw new UnauthorizedException("Account is not active");
        }

        String ip = clientIp(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        AuthResult result = issueTokens(user, ip, userAgent);
        RefreshToken newToken = refreshTokenRepository
                .findByTokenHashAndRevokedFalse(TokenHashing.sha256Hex(result.getRefreshToken()))
                .orElseThrow();
        stored.setReplacedBy(newToken.getId());
        refreshTokenRepository.save(stored);

        writeRefreshCookie(response, result.getRefreshToken());
        auditService.record(
                user.getId(),
                null,
                AuditableAction.TOKEN_REFRESH,
                "User",
                user.getId(),
                null,
                null,
                ip,
                userAgent);
        return result;
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String rawRefresh = resolveRefreshToken(Optional.empty(), request);
        String ip = clientIp(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        UUID userId = null;

        if (StringUtils.hasText(rawRefresh)) {
            String hash = TokenHashing.sha256Hex(rawRefresh);
            Optional<RefreshToken> stored = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash);
            if (stored.isPresent()) {
                RefreshToken token = stored.get();
                token.setRevoked(true);
                refreshTokenRepository.save(token);
                userId = token.getUserId();
            } else if (jwtService.validateRefreshToken(rawRefresh)) {
                userId = jwtService.extractUserIdFromRefreshToken(rawRefresh);
            }
        }

        clearRefreshCookie(response);
        auditService.record(
                userId, null, AuditableAction.LOGOUT, "User", userId, null, null, ip, userAgent);
    }

    @Transactional
    public AuthResult changePassword(UUID userId, ChangePasswordRequest request, HttpServletRequest httpRequest) {
        User user = userRepository
                .findWithRolesById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new ValidationException("Current password is incorrect");
        }
        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new ValidationException("New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(userId);
        auditService.record(
                userId,
                null,
                AuditableAction.PASSWORD_CHANGE,
                "User",
                userId,
                null,
                null,
                clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return issueTokens(user, clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
    }

    @Transactional
    public AuthUserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository
                .findWithRolesById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalseAndIdNot(username, userId)) {
            throw new ConflictException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalseAndIdNot(email, userId)) {
            throw new ConflictException("Email already exists");
        }

        String previous = "{\"username\":\"" + truncate(user.getUsername(), 64) + "\"}";
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        userRepository.save(user);
        auditService.record(
                userId,
                null,
                AuditableAction.UPDATE,
                "User",
                userId,
                previous,
                "{\"username\":\"" + truncate(username, 64) + "\"}",
                null,
                null);
        return toAuthUser(user);
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String identifier = request.getUsernameOrEmail().trim();

        Optional<User> userOpt = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmailIgnoreCaseAndDeletedFalse(identifier);
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String rawToken = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(TokenHashing.sha256Hex(rawToken))
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .used(false)
                    .build();
            passwordResetTokenRepository.save(resetToken);
            auditService.record(
                    user.getId(),
                    null,
                    AuditableAction.PASSWORD_RESET_REQUEST,
                    "User",
                    user.getId(),
                    null,
                    null,
                    ip,
                    userAgent);

            if (isLocalProfile()) {
                log.info(
                        "DEV password reset token for user '{}': {}",
                        user.getUsername(),
                        rawToken);
            }
        } else {
            auditService.record(
                    null,
                    null,
                    AuditableAction.PASSWORD_RESET_REQUEST,
                    "User",
                    null,
                    null,
                    null,
                    ip,
                    userAgent);
        }
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
        String hash = TokenHashing.sha256Hex(request.getToken());
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedFalse(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired reset token"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Invalid or expired reset token");
        }

        User user = userRepository
                .findById(resetToken.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (user.getAccountStatus() == AccountStatus.LOCKED) {
            user.setAccountStatus(AccountStatus.ACTIVE);
        }
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
        refreshTokenRepository.revokeAllByUserId(user.getId());

        auditService.record(
                user.getId(),
                null,
                AuditableAction.PASSWORD_RESET_CONFIRM,
                "User",
                user.getId(),
                null,
                null,
                clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser(UUID userId) {
        User user = userRepository
                .findWithRolesById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        return toAuthUser(user);
    }

    public void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(jwtProperties.getRefreshCookieName(), refreshToken)
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .path("/")
                .maxAge(jwtProperties.getRefreshExpirationMs() / 1000L)
                .sameSite(jwtProperties.getRefreshCookieSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(jwtProperties.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .path("/")
                .maxAge(0)
                .sameSite(jwtProperties.getRefreshCookieSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String getGenericResetMessage() {
        return GENERIC_RESET_MESSAGE;
    }

    private void handleFailedLogin(User user, Instant now, String ip, String userAgent) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        boolean locked = attempts >= appProperties.getMaxFailedLoginAttempts();
        if (locked) {
            user.setAccountStatus(AccountStatus.LOCKED);
            user.setLockedUntil(now.plus(appProperties.getLockDurationMinutes(), ChronoUnit.MINUTES));
        }
        userRepository.save(user);
        auditService.record(
                user.getId(),
                null,
                AuditableAction.LOGIN_FAILURE,
                "User",
                user.getId(),
                null,
                "{\"attempts\":" + attempts + "}",
                ip,
                userAgent);
        if (locked) {
            notificationFacade.notifyUser(
                    user.getId(),
                    null,
                    NotificationType.SECURITY,
                    "Account locked",
                    "Your account was locked after too many failed login attempts. Try again later or reset your password.",
                    "User",
                    user.getId());
        }
    }

    /**
     * Issues access and refresh tokens after membership is already persisted.
     * JWT claims stay limited to identity, roles, permissions, and cooperative ids.
     */
    public AuthResult issueSession(User user, HttpServletRequest httpRequest) {
        return issueTokens(user, clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
    }

    private AuthResult issueTokens(User user, String ip, String userAgent) {
        Set<String> roles = user.getRoleCodes();
        Set<String> permissions = CustomUserDetailsService.extractPermissions(user.getRoles());
        Set<UUID> cooperativeIds = membershipRepository
                .findByUserIdAndMembershipStatus(user.getId(), "ACTIVE")
                .stream()
                .map(CooperativeMembership::getCooperativeId)
                .collect(Collectors.toSet());

        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getUsername(), roles, permissions, cooperativeIds);
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());

        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(TokenHashing.sha256Hex(refreshToken))
                .expiresAt(jwtService.extractRefreshExpiration(refreshToken))
                .revoked(false)
                .ipAddress(truncate(ip, 64))
                .userAgent(truncate(userAgent, 512))
                .build();
        refreshTokenRepository.save(entity);

        AuthUserResponse authUser = toAuthUser(user, roles, permissions, cooperativeIds);
        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessExpirationSeconds())
                .user(authUser)
                .build();

        return AuthResult.builder().response(response).refreshToken(refreshToken).build();
    }

    private AuthUserResponse toAuthUser(User user) {
        Set<String> roles = user.getRoleCodes();
        Set<String> permissions = CustomUserDetailsService.extractPermissions(user.getRoles());
        Set<UUID> cooperativeIds = membershipRepository
                .findByUserIdAndMembershipStatus(user.getId(), "ACTIVE")
                .stream()
                .map(CooperativeMembership::getCooperativeId)
                .collect(Collectors.toCollection(HashSet::new));
        return toAuthUser(user, roles, permissions, cooperativeIds);
    }

    private AuthUserResponse toAuthUser(
            User user, Set<String> roles, Set<String> permissions, Set<UUID> cooperativeIds) {
        List<String> roleList = roles.stream().sorted().toList();
        return AuthUserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .roles(roleList)
                .permissions(permissions)
                .cooperativeIds(cooperativeIds)
                .build();
    }

    private String resolveRefreshToken(Optional<String> bodyToken, HttpServletRequest request) {
        if (bodyToken != null && bodyToken.filter(StringUtils::hasText).isPresent()) {
            return bodyToken.get().trim();
        }
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(c -> jwtProperties.getRefreshCookieName().equals(c.getName()))
                    .map(Cookie::getValue)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private boolean isLocalProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "local".equalsIgnoreCase(p) || "dev".equalsIgnoreCase(p));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
