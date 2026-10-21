package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.repository.RoleRepository;

class CooperativeScopeFilterTest {

    private static final UUID COOP = UUID.fromString("a1b2c3d4-e5f6-4789-abcd-0123456789ab");
    private static final String BASE = "/api/v1/cooperatives/";

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private CooperativeMembershipRepository memberships;
    private CooperativeScopeFilter filter;
    private UUID userId;
    private UserPrincipal global;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        memberships = mock(CooperativeMembershipRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByCode(any(String.class))).thenReturn(Optional.empty());
        when(memberships.findByCooperativeIdAndUserId(COOP, userId))
                .thenReturn(Optional.of(CooperativeMembership.builder()
                        .userId(userId)
                        .cooperativeId(COOP)
                        .membershipStatus("ACTIVE")
                        .roleInCooperative("MEMBER")
                        .build()));
        filter = new CooperativeScopeFilter(new CooperativeAccessResolver(memberships, roles), objectMapper);

        global = UserPrincipal.builder()
                .id(userId)
                .username("x")
                .password("")
                .roles(Set.of("MEMBER", "ACCOUNTANT"))
                .permissions(Set.of("CONTRIBUTION_WRITE"))
                .cooperativeIds(Set.of(COOP))
                .accountNonLocked(true)
                .enabled(true)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(global, null, global.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void plainUuid_isScopedToMembershipRole() throws Exception {
        MockFilterChain chain = run(BASE + COOP + "/contributions/period");

        assertThat(chain.getRequest()).as("request continued").isNotNull();
        UserPrincipal scoped = currentPrincipal();
        assertThat(scoped.getScopedCooperativeId()).isEqualTo(COOP);
        assertThat(scoped.hasRole("ACCOUNTANT")).isFalse();
        assertThat(scoped.hasAuthority("CONTRIBUTION_WRITE")).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .noneMatch(a -> a.getAuthority().equals("CONTRIBUTION_WRITE"));
    }

    @Test
    void uppercaseUuid_isScoped() throws Exception {
        MockFilterChain chain = run(BASE + COOP.toString().toUpperCase() + "/loans");

        assertThat(chain.getRequest()).isNotNull();
        assertThat(currentPrincipal().getScopedCooperativeId()).isEqualTo(COOP);
    }

    @Test
    void firstCharacterEncoded_isRejected() throws Exception {
        assertRejected(BASE + "%61" + COOP.toString().substring(1) + "/contributions/period");
    }

    @Test
    void hyphenEncoded_isRejected() throws Exception {
        assertRejected(BASE + COOP.toString().replace("-", "%2d") + "/contributions/period");
    }

    @Test
    void hexLetterEncoded_isRejected() throws Exception {
        assertRejected(BASE + COOP.toString().replace("b", "%62") + "/contributions/period");
    }

    @Test
    void malformedUuid_isRejected() throws Exception {
        assertRejected(BASE + "not-a-uuid/contributions/period");
        assertRejected(BASE + COOP.toString().substring(1) + "/contributions/period");
    }

    @Test
    void invalidSegment_isRejectedEvenWithoutAuthentication() throws Exception {
        SecurityContextHolder.clearContext();

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(BASE + "%61" + COOP.toString().substring(1) + "/fines"), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void mineAndCollection_areNotRejectedAndNotScoped() throws Exception {
        for (String path : new String[] {"/api/v1/cooperatives", "/api/v1/cooperatives/", BASE + "mine"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request(path), response, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(chain.getRequest()).as(path).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .as(path)
                    .isSameAs(global);
        }
        verifyNoInteractions(memberships);
    }

    @Test
    void unrelatedPaths_areUnaffected() throws Exception {
        for (String path : new String[] {
            "/api/v1/notifications",
            "/api/v1/notifications/pending-approvals",
            "/api/v1/auth/me",
            "/api/v1/files/cooperatives/" + COOP + "/x.pdf",
            "/api/v1/platform/dashboard/overview",
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request(path), response, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(chain.getRequest()).as(path).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .as(path)
                    .isSameAs(global);
        }
        verifyNoInteractions(memberships);
    }

    private void assertRejected(String path) throws Exception {
        UsernamePasswordAuthenticationToken before =
                new UsernamePasswordAuthenticationToken(global, null, global.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(before);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(path), response, chain);

        assertThat(response.getStatus()).as(path).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains(CooperativeScopeFilter.INVALID_PATH_MESSAGE);
        assertThat(chain.getRequest()).as("must never reach the controller: " + path).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(before);
    }

    private MockFilterChain run(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(path), new MockHttpServletResponse(), chain);
        return chain;
    }

    private static UserPrincipal currentPrincipal() {
        return (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
        request.setRequestURI(uri);
        return request;
    }
}
