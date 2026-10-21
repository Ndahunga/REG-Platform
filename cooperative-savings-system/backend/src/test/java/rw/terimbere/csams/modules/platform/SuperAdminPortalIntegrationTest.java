package rw.terimbere.csams.modules.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.terimbere.csams.configuration.JwtProperties;
import rw.terimbere.csams.modules.auth.repository.RefreshTokenRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.JwtService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuperAdminPortalIntegrationTest {

    private static final String PASSWORD = "AdminPass1!";
    private static final String BASE = "/api/v1/platform/super-admins";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Super admins that were ACTIVE before a test narrowed the set; restored afterwards. */
    private Set<UUID> deactivatedForTest = new HashSet<>();

    @BeforeEach
    void setUp() {
        deactivatedForTest = new HashSet<>();
    }

    @AfterEach
    void restoreSuperAdmins() {
        for (UUID id : deactivatedForTest) {
            User user = userRepository.findById(id).orElseThrow();
            user.setAccountStatus(AccountStatus.ACTIVE);
            userRepository.save(user);
        }
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void list_isSuperAdminOnly() throws Exception {
        TestUser admin = newUser("SUPER_ADMIN");
        TestUser president = newUser("PRESIDENT");
        TestUser legacy = newUser("COOPERATIVE_ADMIN");
        TestUser member = newUser("MEMBER");

        assertThat(status(get(BASE).header("Authorization", bearer(login(admin))))).isEqualTo(200);
        assertThat(status(get(BASE).header("Authorization", bearer(login(president))))).isEqualTo(403);
        assertThat(status(get(BASE).header("Authorization", bearer(login(legacy))))).isEqualTo(403);
        assertThat(status(get(BASE).header("Authorization", bearer(login(member))))).isEqualTo(403);
        assertThat(status(get(BASE))).isEqualTo(401);
    }

    @Test
    void createAndStatus_areSuperAdminOnly() throws Exception {
        TestUser admin = newUser("SUPER_ADMIN");
        TestUser president = newUser("PRESIDENT");
        String presidentToken = login(president);

        assertThat(status(post(BASE).header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("x"), unique("x") + "@test.local"))))
                .isEqualTo(403);
        assertThat(status(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("x"), unique("x") + "@test.local"))))
                .isEqualTo(401);
        assertThat(status(patch(BASE + "/" + admin.id() + "/status")
                        .header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}")))
                .isEqualTo(403);
        assertThat(userRepository.findById(admin.id()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void list_returnsSafeFieldsAndMarksCurrentUser() throws Exception {
        TestUser admin = newUser("SUPER_ADMIN");
        MvcResult result = mockMvc.perform(get(BASE).header("Authorization", bearer(login(admin))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("passwordHash").doesNotContain("password").doesNotContain("encoded");

        JsonNode rows = objectMapper.readTree(body).path("data");
        JsonNode self = null;
        for (JsonNode row : rows) {
            if (admin.id().toString().equals(row.path("id").asText())) {
                self = row;
            } else {
                assertThat(row.path("currentUser").asBoolean()).isFalse();
            }
        }
        assertThat(self).isNotNull();
        assertThat(self.path("currentUser").asBoolean()).isTrue();
        assertThat(self.path("username").asText()).isEqualTo(admin.username());
        assertThat(self.path("accountStatus").asText()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ creation

    @Test
    void create_makesAPlainSystemSuperAdmin() throws Exception {
        String token = login(newUser("SUPER_ADMIN"));
        long cooperativesBefore = count("cooperatives");
        long subscriptionsBefore = count("cooperative_subscriptions");
        long membershipsBefore = count("cooperative_memberships");
        String username = unique("sysadmin");
        String email = username.toUpperCase() + "@Test.Local";

        MvcResult result = mockMvc.perform(post(BASE)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":" Sys ","lastName":"Admin","username":" %s ","email":"%s",
                                 "phone":"0781234567","password":"%s",
                                 "roles":["PRESIDENT","MEMBER"]}
                                """.formatted(username, email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash");

        User created = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username).orElseThrow();
        assertThat(created.getRoleCodes()).isEqualTo(Set.of("SUPER_ADMIN"));
        assertThat(created.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(created.getFirstName()).isEqualTo("Sys");
        assertThat(created.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, created.getPasswordHash())).isTrue();

        assertThat(membershipRepository.findByUserIdAndMembershipStatus(created.getId(), "ACTIVE")).isEmpty();
        assertThat(count("cooperative_memberships")).isEqualTo(membershipsBefore);
        assertThat(count("cooperatives")).isEqualTo(cooperativesBefore);
        assertThat(count("cooperative_subscriptions")).isEqualTo(subscriptionsBefore);

        Long audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'CREATE'",
                Long.class,
                created.getId());
        assertThat(audits).isEqualTo(1L);
        String auditJson = jdbcTemplate.queryForObject(
                "SELECT CAST(new_values AS VARCHAR) FROM audit_logs WHERE entity_id = ?",
                String.class,
                created.getId());
        assertThat(auditJson).doesNotContain(PASSWORD);
    }

    @Test
    void create_rejectsDuplicateUsernameAndEmail() throws Exception {
        String token = login(newUser("SUPER_ADMIN"));
        TestUser existing = newUser("MEMBER");

        assertThat(status(post(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(existing.username().toUpperCase(), unique("e") + "@test.local"))))
                .isEqualTo(409);
        assertThat(status(post(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("u"), existing.email().toUpperCase()))))
                .isEqualTo(409);
    }

    @Test
    void create_validatesInput() throws Exception {
        String token = login(newUser("SUPER_ADMIN"));
        assertThat(status(post(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"A\",\"username\":\"abc\","
                                + "\"email\":\"not-an-email\",\"password\":\"short\"}")))
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ login of a new admin

    @Test
    void newAdmin_logsInThroughNormalEndpointWithoutAnyCooperative() throws Exception {
        String token = login(newUser("SUPER_ADMIN"));
        String username = unique("fresh");
        assertThat(status(post(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(username, username + "@test.local"))))
                .isEqualTo(201);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(login.getResponse().getContentAsString()).path("data");
        List<String> roles = new java.util.ArrayList<>();
        data.path("user").path("roles").forEach(r -> roles.add(r.asText()));
        assertThat(roles).containsExactly("SUPER_ADMIN");
        assertThat(data.path("user").path("cooperativeIds")).isEmpty();
        assertThat(jwtService.extractRoles(data.path("accessToken").asText())).contains("SUPER_ADMIN");
        assertThat(jwtService.extractCooperativeIds(data.path("accessToken").asText())).isEmpty();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void deactivate_rejectsSelf() throws Exception {
        TestUser admin = newUser("SUPER_ADMIN");
        assertThat(setStatus(login(admin), admin.id(), "INACTIVE")).isEqualTo(409);
        assertThat(userRepository.findById(admin.id()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void deactivate_rejectsTheLastActiveSuperAdmin() throws Exception {
        TestUser a = newUser("SUPER_ADMIN");
        TestUser b = newUser("SUPER_ADMIN");
        String tokenB = login(b);
        deactivateAllSuperAdminsExcept(a.id(), b.id());

        // A is deactivated by B (allowed: B stays active) ...
        assertThat(setStatus(tokenB, a.id(), "INACTIVE")).isEqualTo(200);
        // ... then B is the only active one, and cannot be deactivated by anyone (self rule), and a deactivated
        // admin with a stale token cannot do it either.
        assertThat(setStatus(tokenB, b.id(), "INACTIVE")).isEqualTo(409);
        assertThat(userRepository.countSuperAdminsWithStatus(AccountStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void deactivate_revokesRefreshTokens_blocksLoginAndRefresh_andReactivationRestoresAccess() throws Exception {
        TestUser a = newUser("SUPER_ADMIN");
        TestUser b = newUser("SUPER_ADMIN");
        String tokenA = login(a);

        MvcResult bLogin = loginResult(b);
        Cookie bRefresh = bLogin.getResponse().getCookie(jwtProperties.getRefreshCookieName());
        assertThat(bRefresh).isNotNull();
        assertThat(refreshTokenRepository.findAll().stream()
                        .filter(t -> t.getUserId().equals(b.id()) && !t.isRevoked()))
                .isNotEmpty();

        assertThat(setStatus(tokenA, b.id(), "INACTIVE")).isEqualTo(200);

        assertThat(userRepository.findById(b.id()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.INACTIVE);
        assertThat(refreshTokenRepository.findAll().stream()
                        .filter(t -> t.getUserId().equals(b.id()) && !t.isRevoked()))
                .isEmpty();
        assertThat(rawLogin(b)).isEqualTo(401);
        assertThat(status(post("/api/v1/auth/refresh").cookie(bRefresh))).isEqualTo(401);

        assertThat(setStatus(tokenA, b.id(), "ACTIVE")).isEqualTo(200);
        assertThat(rawLogin(b)).isEqualTo(200);
    }

    @Test
    void staleAccessToken_isRejectedOnTheNextRequest_includingCooperativeApis() throws Exception {
        // 1. Admin A creates Admin B.
        TestUser a = newUser("SUPER_ADMIN");
        String tokenA = login(a);
        String username = unique("bee");
        assertThat(status(post(BASE).header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(username, username + "@test.local"))))
                .isEqualTo(201);
        User b = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username).orElseThrow();

        // 2. B logs in and holds a valid, unexpired access token.
        String tokenB = login(username, PASSWORD);
        UUID coop = createCooperative(tokenA);
        assertThat(status(get(BASE).header("Authorization", bearer(tokenB)))).isEqualTo(200);
        assertThat(status(get("/api/v1/cooperatives/" + coop + "/dashboard/summary")
                        .header("Authorization", bearer(tokenB))))
                .isEqualTo(200);
        assertThat(status(get("/api/v1/system/info").header("Authorization", bearer(tokenB))))
                .isEqualTo(200);

        // 3. A deactivates B.
        assertThat(setStatus(tokenA, b.getId(), "INACTIVE")).isEqualTo(200);

        // 4. B reuses the same token: refused on the very next request, platform API and cooperative API alike.
        assertThat(status(get(BASE).header("Authorization", bearer(tokenB)))).isEqualTo(401);
        assertThat(status(get("/api/v1/cooperatives/" + coop + "/dashboard/summary")
                        .header("Authorization", bearer(tokenB))))
                .isEqualTo(401);
        assertThat(status(get("/api/v1/cooperatives").header("Authorization", bearer(tokenB))))
                .isEqualTo(401);
        assertThat(status(get("/api/v1/system/info").header("Authorization", bearer(tokenB))))
                .isEqualTo(401);
        // Other admins are unaffected.
        assertThat(status(get(BASE).header("Authorization", bearer(tokenA)))).isEqualTo(200);
    }

    @Test
    void ordinaryUsersAreNotAffectedByTheSuperAdminStatusCheck() throws Exception {
        TestUser member = newUser("MEMBER");
        String token = login(member);
        assertThat(status(get("/api/v1/notifications/unread-count").header("Authorization", bearer(token))))
                .isEqualTo(200);
    }

    @Test
    void invalidStatusValues_areRejected() throws Exception {
        TestUser a = newUser("SUPER_ADMIN");
        TestUser b = newUser("SUPER_ADMIN");
        String token = login(a);
        assertThat(setStatus(token, b.id(), "LOCKED")).isEqualTo(400);
        assertThat(setStatus(token, UUID.randomUUID(), "INACTIVE")).isEqualTo(404);
        TestUser notAdmin = newUser("MEMBER");
        assertThat(setStatus(token, notAdmin.id(), "INACTIVE")).isEqualTo(404);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void simultaneousDeactivations_cannotLeaveZeroActiveSuperAdmins() throws Exception {
        for (int round = 0; round < 3; round++) {
            TestUser a = newUser("SUPER_ADMIN");
            TestUser b = newUser("SUPER_ADMIN");
            String tokenA = login(a);
            String tokenB = login(b);
            deactivateAllSuperAdminsExcept(a.id(), b.id());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                Callable<Integer> aKillsB = () -> {
                    start.await();
                    return setStatus(tokenA, b.id(), "INACTIVE");
                };
                Callable<Integer> bKillsA = () -> {
                    start.await();
                    return setStatus(tokenB, a.id(), "INACTIVE");
                };
                Future<Integer> first = pool.submit(aKillsB);
                Future<Integer> second = pool.submit(bKillsA);
                start.countDown();
                int s1 = first.get();
                int s2 = second.get();

                assertThat(List.of(s1, s2).stream().filter(s -> s == 200).count())
                        .as("exactly one deactivation may succeed (got %d and %d)", s1, s2)
                        .isEqualTo(1);
                assertThat(userRepository.countSuperAdminsWithStatus(AccountStatus.ACTIVE))
                        .isGreaterThanOrEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            // Put the world back for the next round.
            deactivatedForTest.add(a.id());
            deactivatedForTest.add(b.id());
            restoreSuperAdmins();
            deactivatedForTest = new HashSet<>();
        }
    }

    // ------------------------------------------------------------------ /system/info

    @Test
    void systemInfo_isSuperAdminOnly() throws Exception {
        String path = "/api/v1/system/info";
        assertThat(status(get(path).header("Authorization", bearer(login(newUser("SUPER_ADMIN"))))))
                .isEqualTo(200);
        assertThat(status(get(path).header("Authorization", bearer(login(newUser("PRESIDENT"))))))
                .isEqualTo(403);
        assertThat(status(get(path).header("Authorization", bearer(login(newUser("MEMBER"))))))
                .isEqualTo(403);
        assertThat(status(get(path))).isEqualTo(401);
    }

    // ------------------------------------------------------------------ cooperative member management boundary

    @Test
    void president_cannotChangeAGlobalSystemAdminAccount_butOrdinaryMemberUpdatesStillWork() throws Exception {
        String ownerToken = login(newUser("SUPER_ADMIN"));
        UUID coop = createCooperative(ownerToken);
        TestUser president = registerIn(coop, ownerToken, "PRESIDENT");
        TestUser ordinary = registerIn(coop, ownerToken, "MEMBER");
        String presidentToken = login(president);

        // A system admin who also happens to be a member of this Saving Scheme.
        TestUser systemAdmin = newUser("SUPER_ADMIN");
        membershipRepository.save(CooperativeMembership.builder()
                .userId(systemAdmin.id())
                .cooperativeId(coop)
                .membershipStatus("ACTIVE")
                .membershipDate(LocalDate.now())
                .roleInCooperative("MEMBER")
                .shareCount(1)
                .build());

        String statusPath = "/api/v1/cooperatives/" + coop + "/members/" + systemAdmin.id() + "/status";
        assertThat(status(patch(statusPath)
                        .header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\":\"INACTIVE\"}")))
                .isEqualTo(403);
        assertThat(status(put("/api/v1/cooperatives/" + coop + "/members/" + systemAdmin.id())
                        .header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Hijacked\",\"lastName\":\"Admin\","
                                + "\"email\":\"hijack@test.local\",\"username\":\"hijacked\"}")))
                .isEqualTo(403);
        User unchanged = userRepository.findById(systemAdmin.id()).orElseThrow();
        assertThat(unchanged.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(unchanged.getUsername()).isEqualTo(systemAdmin.username());
        assertThat(unchanged.getEmail()).isEqualTo(systemAdmin.email());

        // Ordinary members are managed exactly as before.
        assertThat(status(patch("/api/v1/cooperatives/" + coop + "/members/" + ordinary.id() + "/status")
                        .header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\":\"INACTIVE\"}")))
                .isEqualTo(200);
        assertThat(userRepository.findById(ordinary.id()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.INACTIVE);
        assertThat(status(put("/api/v1/cooperatives/" + coop + "/members/" + ordinary.id())
                        .header("Authorization", bearer(presidentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Renamed\",\"lastName\":\"Member\",\"email\":\""
                                + ordinary.email() + "\"}")))
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ signup / bootstrap on a populated install

    @Test
    void legacySignup_neverCreatesSuperAdmin() throws Exception {
        String username = unique("legacy");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@test.local","password":"SignupPass1!",
                                 "firstName":"Legacy","lastName":"User"}
                                """.formatted(username, username)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(userRepository
                        .findByUsernameIgnoreCaseAndDeletedFalse(username)
                        .orElseThrow()
                        .getRoleCodes())
                .doesNotContain("SUPER_ADMIN");
    }

    @Test
    void bootstrap_isRejectedOnceAUserExists() throws Exception {
        assertThat(status(post("/api/v1/auth/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@test.local","password":"BootPass1!",
                                 "firstName":"Boot","lastName":"Strap"}
                                """.formatted(unique("boot"), unique("boot")))))
                .isEqualTo(409);
    }

    // ================================================================== helpers

    private record TestUser(UUID id, String username, String email) {}

    private TestUser newUser(String roleCode) {
        String username = unique(roleCode.toLowerCase());
        String email = username + "@test.local";
        User saved = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Test")
                .lastName(roleCode)
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(new HashSet<>(Set.of(roleRepository.findByCode(roleCode).orElseThrow())))
                .build());
        return new TestUser(saved.getId(), username, email);
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String createBody(String username, String email) {
        return """
                {"firstName":"New","lastName":"Admin","username":"%s","email":"%s","password":"%s"}
                """.formatted(username, email, PASSWORD);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int setStatus(String token, UUID target, String newStatus) throws Exception {
        return status(patch(BASE + "/" + target + "/status")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + newStatus + "\"}"));
    }

    private String login(TestUser user) throws Exception {
        return login(user.username(), PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private MvcResult loginResult(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user.username(), PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result;
    }

    private int rawLogin(TestUser user) throws Exception {
        return status(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user.username(), PASSWORD)));
    }

    /** Temporarily deactivates every other active system admin so "the last active admin" is testable. */
    private void deactivateAllSuperAdminsExcept(UUID... keep) {
        Set<UUID> keepIds = Set.of(keep);
        Set<UUID> toDeactivate = userRepository.findAllSuperAdmins().stream()
                .filter(u -> u.getAccountStatus() == AccountStatus.ACTIVE && !keepIds.contains(u.getId()))
                .map(User::getId)
                .collect(Collectors.toSet());
        for (UUID id : toDeactivate) {
            User user = userRepository.findById(id).orElseThrow();
            user.setAccountStatus(AccountStatus.INACTIVE);
            userRepository.save(user);
        }
        deactivatedForTest.addAll(toDeactivate);
    }

    private UUID createCooperative(String superAdminToken) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Portal Coop " + unique("c"))))
                .andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private TestUser registerIn(UUID coop, String superAdminToken, String role) throws Exception {
        String username = unique(role.toLowerCase());
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coop + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Portal","lastName":"%s","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"%s","temporaryPassword":"%s"}
                                """.formatted(role, username, username, role, PASSWORD)))
                .andReturn();
        assertThat(register.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new TestUser(UUID.fromString(data.path("userId").asText()), username, username + "@test.local");
    }
}
