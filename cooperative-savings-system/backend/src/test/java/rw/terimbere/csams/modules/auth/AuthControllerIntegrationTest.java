package rw.terimbere.csams.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.configuration.AppProperties;
import rw.terimbere.csams.configuration.JwtProperties;
import rw.terimbere.csams.modules.auth.config.DefaultAdminInitializer;
import rw.terimbere.csams.modules.auth.entity.PasswordResetToken;
import rw.terimbere.csams.modules.auth.entity.RefreshToken;
import rw.terimbere.csams.modules.auth.repository.PasswordResetTokenRepository;
import rw.terimbere.csams.modules.auth.repository.RefreshTokenRepository;
import rw.terimbere.csams.modules.auth.util.TokenHashing;
import rw.terimbere.csams.modules.notification.account.AccountNotificationCopy;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private CooperativeAuthorizationService cooperativeAuthorizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User lockoutUser;

    @BeforeEach
    void setUp() {
        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        lockoutUser = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("lockoutuser")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("lockoutuser")
                        .email("lockout@test.local")
                        .passwordHash(passwordEncoder.encode("Password1!"))
                        .firstName("Lock")
                        .lastName("Out")
                        .accountStatus(AccountStatus.ACTIVE)
                        .failedLoginAttempts(0)
                        .roles(new HashSet<>(Set.of(memberRole)))
                        .build()));
        lockoutUser.setFailedLoginAttempts(0);
        lockoutUser.setLockedUntil(null);
        lockoutUser.setAccountStatus(AccountStatus.ACTIVE);
        lockoutUser.setPasswordHash(passwordEncoder.encode("Password1!"));
        userRepository.save(lockoutUser);
    }

    @Test
    void login_success_returnsAccessTokenAndUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.username").value("superadmin"))
                .andExpect(jsonPath("$.data.user.roles").isArray())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(jwtProperties.getRefreshCookieName());
    }

    @Test
    void login_badPassword_incrementsAndLocksAfterMaxAttempts() throws Exception {
        int max = appProperties.getMaxFailedLoginAttempts();
        for (int i = 0; i < max; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"lockoutuser","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        User locked = userRepository.findByUsernameIgnoreCaseAndDeletedFalse("lockoutuser").orElseThrow();
        assertThat(locked.getAccountStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(locked.getLockedUntil()).isAfter(Instant.now());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"lockoutuser","password":"Password1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account temporarily locked"));
    }

    @Test
    void refresh_rotatesToken() throws Exception {
        UUID superAdminId = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("superadmin")
                .orElseThrow()
                .getId();
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked = true WHERE user_id = ? AND revoked = false", superAdminId);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        jakarta.servlet.http.Cookie cookie = extractRefreshCookie(login);

        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        jakarta.servlet.http.Cookie newCookie = extractRefreshCookie(refresh);
        assertThat(newCookie.getValue()).isNotEqualTo(cookie.getValue());

        long active = refreshTokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(superAdminId))
                .filter(t -> !t.isRevoked())
                .count();
        assertThat(active).isEqualTo(1);
    }

    @Test
    void logout_revokesRefreshToken() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        jakarta.servlet.http.Cookie cookie = extractRefreshCookie(login);
        String raw = cookie.getValue();
        String hash = TokenHashing.sha256Hex(raw);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie))
                .andExpect(status().isOk());

        RefreshToken token = refreshTokenRepository.findAll().stream()
                .filter(t -> hash.equals(t.getTokenHash()))
                .findFirst()
                .orElseThrow();
        assertThat(token.isRevoked()).isTrue();

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_requiresAuthAndRevokesRefreshTokens() throws Exception {
        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("changepassuser")
                .ifPresentOrElse(
                        existing -> {
                            existing.setPasswordHash(passwordEncoder.encode("OldPass@123!"));
                            userRepository.save(existing);
                        },
                        () -> userRepository.save(User.builder()
                                .username("changepassuser")
                                .email("changepass@test.local")
                                .passwordHash(passwordEncoder.encode("OldPass@123!"))
                                .firstName("Change")
                                .lastName("Pass")
                                .accountStatus(AccountStatus.ACTIVE)
                                .roles(new HashSet<>(Set.of(memberRole)))
                                .build()));

        String access = loginAccessToken("changepassuser", "OldPass@123!");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"OldPass@123!","newPassword":"NewPass@123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"changepassuser","password":"OldPass@123!"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"changepassuser","password":"NewPass@123!"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateMe_updatesUsernameAndEmail() throws Exception {
        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        String username = "profuser" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .passwordHash(passwordEncoder.encode("ProfilePass1!"))
                .firstName("Old")
                .lastName("Name")
                .accountStatus(AccountStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(memberRole)))
                .build());

        String access = loginAccessToken(username, "ProfilePass1!");
        String newUsername = username + "x";

        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s@new.local",
                                  "firstName":"New",
                                  "lastName":"Person"
                                }
                                """.formatted(newUsername, newUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(newUsername))
                .andExpect(jsonPath("$.data.firstName").value("New"))
                .andExpect(jsonPath("$.data.lastName").value("Person"))
                .andExpect(jsonPath("$.data.email").value(newUsername + "@new.local"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"ProfilePass1!"}
                                """.formatted(newUsername)))
                .andExpect(status().isOk());
    }

    @Test
    void updateMe_duplicateUsername_returnsConflict() throws Exception {
        String access = loginAccessToken("superadmin", DefaultAdminInitializer.DEFAULT_PASSWORD);
        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"lockoutuser",
                                  "email":"superadmin@terimbere.local",
                                  "firstName":"Super",
                                  "lastName":"Admin"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void me_returnsCurrentUser() throws Exception {
        String access = loginAccessToken("superadmin", DefaultAdminInitializer.DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("superadmin"))
                .andExpect(jsonPath("$.data.email").value("superadmin@terimbere.local"));
        MvcResult notifications = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper
                .readTree(notifications.getResponse().getContentAsString())
                .path("data")
                .path("content");
        for (JsonNode notification : content) {
            assertThat(notification.path("title").asText())
                    .isNotEqualTo(AccountNotificationCopy.WELCOME_TITLE);
        }
    }

    @Test
    void signup_createsMemberAndReturnsAccessToken() throws Exception {
        String username = "su" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = username + "@test.local";

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s",
                                  "password":"SignupPass1!",
                                  "firstName":"Self",
                                  "lastName":"Signup"
                                }
                                """.formatted(username, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.roles", org.hamcrest.Matchers.hasItem("MEMBER")));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"SignupPass1!"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        String access = objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
        MvcResult notifications = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper
                .readTree(notifications.getResponse().getContentAsString())
                .path("data")
                .path("content");
        long welcomes = 0;
        for (JsonNode notification : content) {
            if (AccountNotificationCopy.WELCOME_TITLE.equals(notification.path("title").asText())) {
                welcomes++;
                assertThat(notification.path("type").asText()).isEqualTo("ACCOUNT");
                assertThat(notification.path("body").asText())
                        .contains("account has been created")
                        .doesNotContain("SignupPass1!");
            }
        }
        assertThat(welcomes).isEqualTo(1);
    }

    @Test
    void signup_duplicateUsername_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"superadmin",
                                  "email":"another-admin@test.local",
                                  "password":"SignupPass1!",
                                  "firstName":"Dup",
                                  "lastName":"User"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void passwordReset_confirm_updatesPassword() throws Exception {
        User user = lockoutUser;
        String rawToken = UUID.randomUUID().toString();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(TokenHashing.sha256Hex(rawToken))
                .expiresAt(Instant.now().plusSeconds(3600))
                .used(false)
                .build());

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"ResetPass1!"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"lockoutuser","password":"ResetPass1!"}
                                """))
                .andExpect(status().isOk());

        // restore for other tests
        user = userRepository.findByUsernameIgnoreCaseAndDeletedFalse("lockoutuser").orElseThrow();
        user.setPasswordHash(passwordEncoder.encode("Password1!"));
        userRepository.save(user);
    }

    @Test
    void cooperativeAccess_superAdminOk_nonMemberForbidden() {
        UUID coopId = UUID.randomUUID();
        insertCooperative(coopId);

        User admin = userRepository.findByUsernameIgnoreCaseAndDeletedFalse("superadmin").orElseThrow();
        UserPrincipal adminPrincipal = UserPrincipal.builder()
                .id(admin.getId())
                .username(admin.getUsername())
                .password("")
                .roles(Set.of("SUPER_ADMIN"))
                .permissions(Set.of("USER_READ"))
                .cooperativeIds(Set.of())
                .accountNonLocked(true)
                .enabled(true)
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        adminPrincipal, null, adminPrincipal.getAuthorities()));
        cooperativeAuthorizationService.requireMembership(coopId);

        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        User member = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("coopmember")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("coopmember")
                        .email("coopmember@test.local")
                        .passwordHash(passwordEncoder.encode("Password1!"))
                        .firstName("Coop")
                        .lastName("Member")
                        .accountStatus(AccountStatus.ACTIVE)
                        .roles(new HashSet<>(Set.of(memberRole)))
                        .build()));

        UUID otherCoop = UUID.randomUUID();
        insertCooperative(otherCoop);
        membershipRepository.save(CooperativeMembership.builder()
                .userId(member.getId())
                .cooperativeId(otherCoop)
                .membershipStatus("ACTIVE")
                .roleInCooperative("MEMBER")
                .build());

        UserPrincipal memberPrincipal = UserPrincipal.builder()
                .id(member.getId())
                .username(member.getUsername())
                .password("")
                .roles(Set.of("MEMBER"))
                .permissions(Set.of())
                .cooperativeIds(Set.of(otherCoop))
                .accountNonLocked(true)
                .enabled(true)
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        memberPrincipal, null, memberPrincipal.getAuthorities()));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cooperativeAuthorizationService.requireMembership(coopId))
                .isInstanceOf(rw.terimbere.csams.shared.exceptions.ForbiddenException.class);

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private String loginAccessToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return readAccessToken(login);
    }

    private String readAccessToken(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    private jakarta.servlet.http.Cookie extractRefreshCookie(MvcResult result) {
        jakarta.servlet.http.Cookie cookie =
                result.getResponse().getCookie(jwtProperties.getRefreshCookieName());
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void insertCooperative(UUID id) {
        jdbcTemplate.update(
                """
                INSERT INTO cooperatives (id, name, currency, financial_year_start_month, monthly_contribution_amount,
                    contribution_due_day, status, operating_model, created_at, updated_at, deleted, version)
                VALUES (?, 'Test Coop', 'RWF', 1, 0, 1, 'ACTIVE', 'ACCUMULATING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 0)
                """,
                id);
    }
}
