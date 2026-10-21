package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;

class CooperativeAuthorizationServiceTest {

    private CooperativeAuthorizationService service;
    private UUID coopA;
    private UUID coopB;

    @BeforeEach
    void setUp() {
        service = new CooperativeAuthorizationService();
        coopA = UUID.randomUUID();
        coopB = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireMembership_allowsSuperAdminWithoutMembership() {
        authenticate(principal("admin", Set.of("SUPER_ADMIN"), Set.of(), Set.of()));
        service.requireMembership(coopA);
    }

    @Test
    void requireMembership_allowsMember() {
        authenticate(principal("member", Set.of("MEMBER"), Set.of("CONTRIBUTION_READ"), Set.of(coopA)));
        service.requireMembership(coopA);
    }

    @Test
    void requireMembership_forbidsNonMember() {
        authenticate(principal("member", Set.of("MEMBER"), Set.of(), Set.of(coopA)));
        assertThatThrownBy(() -> service.requireMembership(coopB))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireMembership_forbidsCooperativeOtherThanTheScopedOne() {
        UserPrincipal scopedToA = principal("accountant", Set.of("MEMBER", "ACCOUNTANT"), Set.of("CONTRIBUTION_WRITE"), Set.of(coopA, coopB))
                .toBuilder()
                .scopedCooperativeId(coopA)
                .build();
        authenticate(scopedToA);

        service.requireMembership(coopA);
        assertThatThrownBy(() -> service.requireMembership(coopB)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void hasRole_and_hasAuthority() {
        authenticate(principal(
                "admin",
                Set.of("COOPERATIVE_ADMIN"),
                Set.of("CONTRIBUTION_WRITE"),
                Set.of(coopA)));

        assertThat(service.hasRole("COOPERATIVE_ADMIN")).isTrue();
        assertThat(service.hasRole("SUPER_ADMIN")).isFalse();
        assertThat(service.hasAuthority("CONTRIBUTION_WRITE")).isTrue();
        assertThat(service.hasAuthority("LOAN_WRITE")).isFalse();
    }

    private UserPrincipal principal(
            String username, Set<String> roles, Set<String> permissions, Set<UUID> coopIds) {
        return UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username(username)
                .password("")
                .roles(roles)
                .permissions(permissions)
                .cooperativeIds(coopIds)
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }

    private void authenticate(UserPrincipal principal) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
