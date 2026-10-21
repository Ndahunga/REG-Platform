package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.service.NotificationWhatsAppService;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * Proves that cooperative-scoped authorization follows the caller's role in the TARGET cooperative
 * ({@code cooperative_memberships.role_in_cooperative}), not global user roles, and that role/status
 * changes apply to already-issued access tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CooperativeScopedAuthorizationIntegrationTest {

    private record Person(UUID userId, String username, String password) {}

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private NotificationWhatsAppService notificationWhatsAppService;

    private String superAdminToken;
    private UUID coopA;
    private UUID coopB;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        coopA = createCooperative("Scope Coop A");
        coopB = createCooperative("Scope Coop B");
    }

    // ---------------------------------------------------------------- Accountant in A, Member in B

    @Test
    void accountantInA_memberInB_canWriteInA_butNotInB() throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);

        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, token, payer.userId())).isEqualTo(403);
        assertThat(fineCreate(coopB, token, payer.userId())).isEqualTo(403);
        assertThat(repaymentCreate(coopB, token)).isEqualTo(403);

        // Allowed in A: authorization passes, so the (non-existent) loan yields 404, not 403.
        assertThat(repaymentCreate(coopA, token)).isEqualTo(404);
        assertThat(fineCreate(coopA, token, payer.userId())).isEqualTo(200);
    }

    // ---------------------------------------------------------------- President in A, Member in B

    @Test
    void presidentInA_memberInB_hasLeadershipOnlyInA() throws Exception {
        Person target = memberInBoth();
        Person president = officerInAMemberInB("PRESIDENT");
        String token = login(president);

        // Leadership action: change a member's status (MEMBERSHIP_MANAGE + leadership).
        assertThat(memberStatus(coopA, token, target.userId(), "ACTIVE")).isEqualTo(200);
        assertThat(memberStatus(coopB, token, target.userId(), "ACTIVE")).isEqualTo(403);

        // Fund authorize (loan write-off): allowed in A (404 = authorized, loan missing), forbidden in B.
        assertThat(writeOff(coopA, token)).isEqualTo(404);
        assertThat(writeOff(coopB, token)).isEqualTo(403);

        // President-only fine configuration.
        assertThat(patchFine(coopB, token)).isEqualTo(403);
        // Cooperative-wide reads that need officer permissions.
        assertThat(getStatus(coopA, token, "/ledger")).isEqualTo(200);
        assertThat(getStatus(coopB, token, "/ledger")).isEqualTo(403);
    }

    // ---------------------------------------------------------------- Member in both

    @Test
    void memberInBoth_hasOnlyMemberPermissions_andCanSelfSubmit() throws Exception {
        Person member = memberInBoth();
        String token = login(member);

        for (UUID coop : new UUID[] {coopA, coopB}) {
            assertThat(contributionWrite(coop, token, member.userId())).isEqualTo(403);
            assertThat(fineCreate(coop, token, member.userId())).isEqualTo(403);
            assertThat(repaymentCreate(coop, token)).isEqualTo(403);
            assertThat(writeOff(coop, token)).isEqualTo(403);
            assertThat(memberStatus(coop, token, member.userId(), "ACTIVE")).isEqualTo(403);
        }

        assertThat(contributionSubmit(coopA, token, 2026, 3)).isEqualTo(200);
        assertThat(contributionSubmit(coopB, token, 2026, 3)).isEqualTo(200);
    }

    // ---------------------------------------------------------------- Super Admin

    @Test
    void superAdmin_keepsGlobalAccessWithoutMembership() throws Exception {
        Person payer = memberInBoth();

        assertThat(contributionWrite(coopA, superAdminToken, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, superAdminToken, payer.userId())).isEqualTo(200);
        assertThat(getStatus(coopB, superAdminToken, "/dashboard/summary")).isEqualTo(200);
        assertThat(writeOff(coopB, superAdminToken)).isEqualTo(404);
        assertThat(memberStatus(coopB, superAdminToken, payer.userId(), "ACTIVE")).isEqualTo(200);
    }

    // ---------------------------------------------------------------- Active-token role changes

    @Test
    void demotionApplies_toAlreadyIssuedAccessToken() throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);

        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);

        assertThat(updateMemberRole(coopA, accountant, "MEMBER")).isEqualTo(200);

        // Same access token, no refresh: the write must now be refused immediately.
        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(403);
        // Member-level access in A remains.
        assertThat(getStatus(coopA, token, "/contributions/my")).isEqualTo(200);
    }

    @Test
    void promotionApplies_toAlreadyIssuedAccessToken() throws Exception {
        Person payer = memberInBoth();
        Person member = memberInBoth();
        String token = login(member);

        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(403);
        assertThat(updateMemberRole(coopA, member, "ACCOUNTANT")).isEqualTo(200);
        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, token, payer.userId())).isEqualTo(403);
    }

    @Test
    void changingRoleInB_doesNotStripRoleInA() throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");

        assertThat(updateMemberRole(coopB, accountant, "MEMBER")).isEqualTo(200);
        assertThat(roleInCooperative(coopA, accountant)).isEqualTo("ACCOUNTANT");
        assertThat(userRepository.findWithRolesById(accountant.userId()).orElseThrow().getRoleCodes())
                .contains("ACCOUNTANT");

        assertThat(updateMemberRole(coopB, accountant, "LOAN_OFFICER")).isEqualTo(200);
        assertThat(roleInCooperative(coopA, accountant)).isEqualTo("ACCOUNTANT");
        assertThat(roleInCooperative(coopB, accountant)).isEqualTo("LOAN_OFFICER");
        assertThat(userRepository.findWithRolesById(accountant.userId()).orElseThrow().getRoleCodes())
                .contains("ACCOUNTANT", "LOAN_OFFICER");

        String freshToken = login(accountant);
        assertThat(contributionWrite(coopA, freshToken, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, freshToken, payer.userId())).isEqualTo(403);
    }

    // ---------------------------------------------------------------- Inactive / suspended membership

    @Test
    void suspendedMembership_isRefusedImmediately() throws Exception {
        assertMembershipStatusBlocksAccess("SUSPENDED");
    }

    @Test
    void inactiveMembership_isRefusedImmediately() throws Exception {
        assertMembershipStatusBlocksAccess("INACTIVE");
    }

    private void assertMembershipStatusBlocksAccess(String membershipStatus) throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);
        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);

        // INACTIVE has its own action (a reason, no payout in flight, audited); the other statuses use the status endpoint
        MvcResult result = "INACTIVE".equals(membershipStatus)
                ? mockMvc.perform(post("/api/v1/cooperatives/" + coopA + "/members/" + accountant.userId() + "/deactivate")
                                .header("Authorization", "Bearer " + superAdminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Left the committee\"}"))
                        .andReturn()
                : mockMvc.perform(patch("/api/v1/cooperatives/" + coopA + "/members/" + accountant.userId() + "/status")
                                .header("Authorization", "Bearer " + superAdminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"membershipStatus\":\"" + membershipStatus + "\"}"))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(403);
        assertThat(getStatus(coopA, token, "/contributions/my")).isEqualTo(403);
        // Role in the other cooperative is unaffected.
        assertThat(getStatus(coopB, token, "/contributions/my")).isEqualTo(200);
    }

    // ---------------------------------------------------------------- Multi-cooperative switching

    @Test
    void sameToken_followsPathCooperative_notFirstOrLastUsed() throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);

        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, token, payer.userId())).isEqualTo(403);
        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);
        assertThat(contributionWrite(coopB, token, payer.userId())).isEqualTo(403);

        // A cooperative where the user has no membership at all.
        UUID coopC = createCooperative("Scope Coop C");
        assertThat(contributionWrite(coopC, token, payer.userId())).isEqualTo(403);
        assertThat(getStatus(coopC, token, "/contributions/my")).isEqualTo(403);
    }

    // ---------------------------------------------------------------- Aggregated queues

    @Test
    void pendingApprovals_countOnlyCooperativesWhereRoleGrantsReview() throws Exception {
        Person submitter = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String accountantToken = login(accountant);
        String submitterToken = login(submitter);

        // Pending submission in B, where the accountant is only a member: must not be counted.
        assertThat(contributionSubmit(coopB, submitterToken, 2026, 4)).isEqualTo(200);
        assertThat(pendingContributionCount(accountantToken)).isZero();

        // Pending submission in A, where the accountant reviews: counted.
        assertThat(contributionSubmit(coopA, submitterToken, 2026, 4)).isEqualTo(200);
        assertThat(pendingContributionCount(accountantToken)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- Encoded cooperative id (fail closed)

    @Test
    void encodedCooperativeId_cannotRestoreGlobalPermissions_andNothingIsWritten() throws Exception {
        Person payer = memberInBoth();
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);
        String b = coopB.toString();

        // Control: the canonical path is refused and writes nothing.
        assertThat(contributionWrite(coopB, token, payer.userId())).isEqualTo(403);
        assertThat(contributionExists(coopB, payer)).isFalse();

        String[] variants = {
            percentEncode(b, 0), // first character encoded
            b.replace("-", "%2d"), // hyphens encoded
            percentEncodeFirstLetter(b), // a hex letter encoded
        };
        for (String variant : variants) {
            assertThat(contributionWriteRaw(variant, token, payer.userId()))
                    .as("encoded cooperative id %s must be rejected, not authorized", variant)
                    .isEqualTo(400);
            assertThat(contributionExists(coopB, payer))
                    .as("no write may happen through %s", variant)
                    .isFalse();
        }
        assertThat(contributionWriteRaw(b.toUpperCase(), token, payer.userId())).isEqualTo(403);
        assertThat(contributionExists(coopB, payer)).isFalse();

        // Fail closed for the caller's own legitimate cooperative too, and for reads.
        assertThat(contributionWriteRaw(percentEncode(coopA.toString(), 0), token, payer.userId()))
                .isEqualTo(400);
        assertThat(contributionExists(coopA, payer)).isFalse();
        assertThat(rawStatus(get(URI.create("/api/v1/cooperatives/" + percentEncode(b, 0) + "/contributions/my"))
                        .header("Authorization", "Bearer " + token)))
                .isEqualTo(400);

        // Control: the canonical path in A still works and does write.
        assertThat(contributionWrite(coopA, token, payer.userId())).isEqualTo(200);
        assertThat(contributionExists(coopA, payer)).isTrue();
    }

    @Test
    void collectionAndMineEndpoints_stillWork() throws Exception {
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        String token = login(accountant);

        assertThat(rawStatus(get(URI.create("/api/v1/cooperatives/mine")).header("Authorization", "Bearer " + token)))
                .isEqualTo(200);
        assertThat(rawStatus(get(URI.create("/api/v1/cooperatives")).header("Authorization", "Bearer " + token)))
                .isEqualTo(200);
    }

    @Test
    void subscriptionEnforcement_cannotBeBypassedWithEncodedCooperativeId() throws Exception {
        UUID unsubscribed = createCooperativeWithoutSubscription();
        Person accountant = registerIn(unsubscribed, "ACCOUNTANT");
        OpeningShareBalances.set(membershipRepository, unsubscribed, accountant.userId(), 1);
        String token = login(accountant);
        String id = unsubscribed.toString();

        // Canonical write to a cooperative without an active subscription: 402, nothing written.
        assertThat(contributionWrite(unsubscribed, token, accountant.userId())).isEqualTo(402);
        assertThat(contributionExists(unsubscribed, accountant)).isFalse();

        String[] variants = {percentEncode(id, 0), id.replace("-", "%2d"), percentEncodeFirstLetter(id)};
        for (String variant : variants) {
            assertThat(contributionWriteRaw(variant, token, accountant.userId()))
                    .as("encoded cooperative id %s must never return 200", variant)
                    .isEqualTo(400);
            assertThat(contributionExists(unsubscribed, accountant))
                    .as("no data mutation through %s", variant)
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------- Officer notification recipients

    @Test
    void officerNotificationRecipients_followTheRoleInEachCooperative() throws Exception {
        Person accountant = officerInAMemberInB("ACCOUNTANT");
        Person loanOfficer = officerInAMemberInB("LOAN_OFFICER");
        Person president = officerInAMemberInB("PRESIDENT");
        Person plainMember = memberInBoth();

        Set<UUID> contributionReviewersA = notificationWhatsAppService.officerRecipientIds(coopA, "CONTRIBUTION_WRITE");
        Set<UUID> contributionReviewersB = notificationWhatsAppService.officerRecipientIds(coopB, "CONTRIBUTION_WRITE");
        assertThat(contributionReviewersA).contains(accountant.userId(), president.userId());
        assertThat(contributionReviewersA).doesNotContain(loanOfficer.userId(), plainMember.userId());
        assertThat(contributionReviewersB)
                .doesNotContain(accountant.userId(), president.userId(), loanOfficer.userId(), plainMember.userId());

        Set<UUID> loanApproversA = notificationWhatsAppService.officerRecipientIds(coopA, "LOAN_APPROVE");
        Set<UUID> loanApproversB = notificationWhatsAppService.officerRecipientIds(coopB, "LOAN_APPROVE");
        assertThat(loanApproversA).contains(loanOfficer.userId(), president.userId());
        assertThat(loanApproversA).doesNotContain(accountant.userId(), plainMember.userId());
        assertThat(loanApproversB)
                .doesNotContain(loanOfficer.userId(), president.userId(), accountant.userId(), plainMember.userId());

        // Promoting the accountant in B makes them a reviewer there, and only there.
        assertThat(updateMemberRole(coopB, accountant, "ACCOUNTANT")).isEqualTo(200);
        assertThat(notificationWhatsAppService.officerRecipientIds(coopB, "CONTRIBUTION_WRITE"))
                .contains(accountant.userId());

        // A suspended officer no longer receives officer notifications for that cooperative.
        MvcResult suspend = mockMvc.perform(patch("/api/v1/cooperatives/" + coopA + "/members/"
                                + accountant.userId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipStatus\":\"SUSPENDED\"}"))
                .andReturn();
        assertThat(suspend.getResponse().getStatus()).isEqualTo(200);
        assertThat(notificationWhatsAppService.officerRecipientIds(coopA, "CONTRIBUTION_WRITE"))
                .doesNotContain(accountant.userId());
    }

    @Test
    void officerNotificationRecipients_keepActiveSuperAdminMembers() throws Exception {
        Person superAdminMember = registerIn(coopA, "MEMBER");
        User user = userRepository.findWithRolesById(superAdminMember.userId()).orElseThrow();
        user.getRoles().add(roleRepository.findByCode("SUPER_ADMIN").orElseThrow());
        userRepository.save(user);

        assertThat(notificationWhatsAppService.officerRecipientIds(coopA, "CONTRIBUTION_WRITE"))
                .contains(superAdminMember.userId());
        assertThat(notificationWhatsAppService.officerRecipientIds(coopB, "CONTRIBUTION_WRITE"))
                .doesNotContain(superAdminMember.userId());
    }

    // ================================================================ helpers

    private UUID createCooperativeWithoutSubscription() throws Exception {
        String name = "Scope Unsubscribed " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                name, "\"subscriptionInitialization\":\"NONE\"")))
                .andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private UUID createCooperative(String namePrefix) throws Exception {
        String name = namePrefix + " " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    /** Registers a user in cooperative A with the given role (the normal, supported way to create a user). */
    private Person registerInA(String role) throws Exception {
        return registerIn(coopA, role);
    }

    private Person registerIn(UUID coop, String role) throws Exception {
        String username = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coop + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Scope",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(role, username, username, role)))
                .andReturn();
        assertThat(register.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Person(
                UUID.fromString(data.path("userId").asText()),
                username,
                data.path("temporaryPassword").asText());
    }

    /**
     * Adds an existing user to cooperative B as MEMBER. This is the state historical import produces for an
     * existing platform user, and the only way a user can hold different roles in two cooperatives.
     */
    private void addToBAsMember(Person person) {
        membershipRepository.save(CooperativeMembership.builder()
                .userId(person.userId())
                .cooperativeId(coopB)
                .membershipStatus("ACTIVE")
                .membershipDate(LocalDate.now())
                .roleInCooperative("MEMBER")
                .shareCount(1)
                .build());
    }

    private Person officerInAMemberInB(String role) throws Exception {
        Person person = registerInA(role);
        addToBAsMember(person);
        return person;
    }

    /** A plain member of both cooperatives, holding one share in each. */
    private Person memberInBoth() throws Exception {
        Person person = registerInA("MEMBER");
        OpeningShareBalances.set(membershipRepository, coopA, person.userId(), 1);
        addToBAsMember(person);
        return person;
    }

    private String roleInCooperative(UUID coop, Person person) {
        return membershipRepository
                .findByCooperativeIdAndUserId(coop, person.userId())
                .orElseThrow()
                .getRoleInCooperative();
    }

    private int updateMemberRole(UUID coop, Person person, String role) throws Exception {
        return status(put("/api/v1/cooperatives/" + coop + "/members/" + person.userId())
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "firstName":"Scope",
                          "lastName":"Updated",
                          "email":"%s@test.local",
                          "roleInCooperative":"%s"
                        }
                        """.formatted(person.username(), role)));
    }

    private int contributionWrite(UUID coop, String token, UUID memberUserId) throws Exception {
        return status(put("/api/v1/cooperatives/" + coop + "/contributions/period")
                .header("Authorization", "Bearer " + token)
                .param("year", "2026")
                .param("month", "3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lines":[{"memberUserId":"%s","paidAmount":1000.0000,"paymentDate":"2026-03-05"}]}
                        """.formatted(memberUserId)));
    }

    /** Writes through a caller-supplied (possibly percent-encoded) cooperative path segment. */
    private int contributionWriteRaw(String rawCooperativeSegment, String token, UUID memberUserId) throws Exception {
        return rawStatus(put(URI.create("/api/v1/cooperatives/" + rawCooperativeSegment
                        + "/contributions/period?year=2026&month=3"))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lines":[{"memberUserId":"%s","paidAmount":1000.0000,"paymentDate":"2026-03-05"}]}
                        """.formatted(memberUserId)));
    }

    private boolean contributionExists(UUID coop, Person member) {
        return contributionRepository.existsByCooperativeIdAndMemberUserIdAndYearAndMonth(
                coop, member.userId(), 2026, 3);
    }

    private static String percentEncode(String value, int index) {
        return value.substring(0, index)
                + String.format("%%%02x", (int) value.charAt(index))
                + value.substring(index + 1);
    }

    private static String percentEncodeFirstLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return percentEncode(value, i);
            }
        }
        return percentEncode(value, 0);
    }

    private int rawStatus(MockHttpServletRequestBuilder request) throws Exception {
        return status(request);
    }

    private int contributionSubmit(UUID coop, String token, int year, int month) throws Exception {
        return status(post("/api/v1/cooperatives/" + coop + "/contributions/submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"year":%d,"month":%d,"amount":500.0000,"paymentDate":"2026-03-04"}
                        """.formatted(year, month)));
    }

    private int fineCreate(UUID coop, String token, UUID memberUserId) throws Exception {
        return status(post("/api/v1/cooperatives/" + coop + "/fines")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"memberUserId":"%s","amount":100.00,"reason":"scope test","issuedDate":"%s"}
                        """.formatted(memberUserId, LocalDate.now())));
    }

    private int repaymentCreate(UUID coop, String token) throws Exception {
        return status(post("/api/v1/cooperatives/" + coop + "/loans/" + UUID.randomUUID() + "/repayments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":100.00,"paymentDate":"%s"}
                        """.formatted(LocalDate.now())));
    }

    private int writeOff(UUID coop, String token) throws Exception {
        return status(post("/api/v1/cooperatives/" + coop + "/loans/" + UUID.randomUUID() + "/write-off")
                .header("Authorization", "Bearer " + token));
    }

    private int memberStatus(UUID coop, String token, UUID memberUserId, String membershipStatus) throws Exception {
        return status(patch("/api/v1/cooperatives/" + coop + "/members/" + memberUserId + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipStatus\":\"" + membershipStatus + "\"}"));
    }

    private int patchFine(UUID coop, String token) throws Exception {
        return status(patch("/api/v1/cooperatives/" + coop + "/fines/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private int getStatus(UUID coop, String token, String suffix) throws Exception {
        return status(get("/api/v1/cooperatives/" + coop + suffix).header("Authorization", "Bearer " + token));
    }

    private long pendingContributionCount(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("contributionPendingCount")
                .asLong();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String login(Person person) throws Exception {
        return login(person.username(), person.password());
    }

    private String login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
