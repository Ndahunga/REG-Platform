package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Permission;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;

class CooperativeAccessResolverTest {

    private CooperativeMembershipRepository membershipRepository;
    private RoleRepository roleRepository;
    private CooperativeAccessResolver resolver;
    private UUID userId;
    private UUID coopA;
    private UUID coopB;
    private final java.util.Map<String, Role> roles = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        membershipRepository = mock(CooperativeMembershipRepository.class);
        roleRepository = mock(RoleRepository.class);
        resolver = new CooperativeAccessResolver(membershipRepository, roleRepository);
        when(roleRepository.findByCode(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(roles.get((String) invocation.getArgument(0))));
        userId = UUID.randomUUID();
        coopA = UUID.randomUUID();
        coopB = UUID.randomUUID();

        stubRole("MEMBER", "CONTRIBUTION_READ", "LOAN_READ");
        stubRole("ACCOUNTANT", "CONTRIBUTION_WRITE", "FINE_WRITE", "LOAN_WRITE");
        stubRole("PRESIDENT", "CONTRIBUTION_WRITE", "MEMBERSHIP_MANAGE", "FUND_AUTHORIZE", "LOAN_APPROVE");
    }

    @Test
    void accountantMembership_getsMemberPlusAccountantPermissions() {
        stubMembership(coopA, "ACTIVE", "ACCOUNTANT");

        var access = resolver.resolve(userId, coopA);

        assertThat(access.activeMember()).isTrue();
        assertThat(access.roles()).containsExactlyInAnyOrder("MEMBER", "ACCOUNTANT");
        assertThat(access.permissions())
                .containsExactlyInAnyOrder("CONTRIBUTION_READ", "LOAN_READ", "CONTRIBUTION_WRITE", "FINE_WRITE", "LOAN_WRITE");
    }

    @Test
    void memberMembership_getsOnlyMemberPermissions() {
        stubMembership(coopB, "ACTIVE", "MEMBER");

        var access = resolver.resolve(userId, coopB);

        assertThat(access.roles()).containsExactly("MEMBER");
        assertThat(access.permissions()).containsExactlyInAnyOrder("CONTRIBUTION_READ", "LOAN_READ");
    }

    @Test
    void legacyCooperativeAdmin_mapsToPresident() {
        stubMembership(coopA, "ACTIVE", "COOPERATIVE_ADMIN");

        var access = resolver.resolve(userId, coopA);

        assertThat(access.membershipRole()).isEqualTo("PRESIDENT");
        assertThat(access.permissions()).contains("FUND_AUTHORIZE", "LOAN_APPROVE");
    }

    @Test
    void blankOrUnknownRole_failsClosedToMember() {
        stubMembership(coopA, "ACTIVE", null);
        assertThat(resolver.resolve(userId, coopA).permissions()).doesNotContain("CONTRIBUTION_WRITE");

        stubMembership(coopA, "ACTIVE", "GOD_MODE");
        var access = resolver.resolve(userId, coopA);
        assertThat(access.membershipRole()).isEqualTo("MEMBER");
        assertThat(access.permissions()).doesNotContain("CONTRIBUTION_WRITE", "FUND_AUTHORIZE");
    }

    @Test
    void suspendedInactiveOrMissingMembership_getsNothing() {
        for (String status : new String[] {"SUSPENDED", "INACTIVE", "PENDING"}) {
            stubMembership(coopA, status, "PRESIDENT");
            var access = resolver.resolve(userId, coopA);
            assertThat(access.activeMember()).isFalse();
            assertThat(access.roles()).isEmpty();
            assertThat(access.permissions()).isEmpty();
        }

        when(membershipRepository.findByCooperativeIdAndUserId(coopB, userId)).thenReturn(Optional.empty());
        assertThat(resolver.resolve(userId, coopB).permissions()).isEmpty();
    }

    @Test
    void permissionsForMembershipRole_followsTheRoleNotTheUser() {
        assertThat(resolver.permissionsForMembershipRole("ACCOUNTANT")).contains("CONTRIBUTION_WRITE", "FINE_WRITE");
        assertThat(resolver.permissionsForMembershipRole("MEMBER"))
                .containsExactlyInAnyOrder("CONTRIBUTION_READ", "LOAN_READ");
        assertThat(resolver.permissionsForMembershipRole(null)).doesNotContain("CONTRIBUTION_WRITE");
        assertThat(resolver.permissionsForMembershipRole("GOD_MODE")).doesNotContain("CONTRIBUTION_WRITE");
        assertThat(resolver.permissionsForMembershipRole("COOPERATIVE_ADMIN")).contains("FUND_AUTHORIZE");
    }

    @Test
    void scope_replacesGlobalRolesWithTargetCooperativeRole() {
        stubMembership(coopA, "ACTIVE", "ACCOUNTANT");
        stubMembership(coopB, "ACTIVE", "MEMBER");
        UserPrincipal global = globalPrincipal(Set.of("MEMBER", "ACCOUNTANT"), Set.of(coopA, coopB));

        UserPrincipal inA = resolver.scope(global, coopA);
        UserPrincipal inB = resolver.scope(global, coopB);

        assertThat(inA.hasAuthority("CONTRIBUTION_WRITE")).isTrue();
        assertThat(inA.hasRole("ACCOUNTANT")).isTrue();
        assertThat(inA.getScopedCooperativeId()).isEqualTo(coopA);

        assertThat(inB.hasAuthority("CONTRIBUTION_WRITE")).isFalse();
        assertThat(inB.hasRole("ACCOUNTANT")).isFalse();
        assertThat(inB.hasAuthority("CONTRIBUTION_READ")).isTrue();
        assertThat(CooperativeOfficerRoles.isOfficer(inB)).isFalse();
        assertThat(CooperativeOfficerRoles.isOfficer(inA)).isTrue();
    }

    @Test
    void scope_dropsCooperativeWhenMembershipNotActive_evenIfTokenStillListsIt() {
        stubMembership(coopA, "SUSPENDED", "ACCOUNTANT");
        UserPrincipal global = globalPrincipal(Set.of("MEMBER", "ACCOUNTANT"), Set.of(coopA, coopB));

        UserPrincipal inA = resolver.scope(global, coopA);

        assertThat(inA.isMemberOf(coopA)).isFalse();
        assertThat(inA.isMemberOf(coopB)).isTrue();
        assertThat(inA.getPermissionCodes()).isEmpty();
    }

    @Test
    void scope_addsNewlyActiveMembershipMissingFromStaleToken() {
        stubMembership(coopB, "ACTIVE", "MEMBER");
        UserPrincipal global = globalPrincipal(Set.of("MEMBER"), Set.of(coopA));

        assertThat(resolver.scope(global, coopB).isMemberOf(coopB)).isTrue();
    }

    @Test
    void scope_leavesSuperAdminUntouched() {
        UserPrincipal superAdmin = globalPrincipal(Set.of("SUPER_ADMIN"), Set.of());

        assertThat(resolver.scope(superAdmin, coopA)).isSameAs(superAdmin);
        verifyNoInteractions(membershipRepository);
    }

    private void stubMembership(UUID coop, String status, String role) {
        when(membershipRepository.findByCooperativeIdAndUserId(coop, userId))
                .thenReturn(Optional.of(CooperativeMembership.builder()
                        .userId(userId)
                        .cooperativeId(coop)
                        .membershipStatus(status)
                        .roleInCooperative(role)
                        .build()));
    }

    private void stubRole(String code, String... permissionCodes) {
        Set<Permission> permissions = new java.util.HashSet<>();
        for (String permissionCode : permissionCodes) {
            permissions.add(Permission.builder().code(permissionCode).name(permissionCode).build());
        }
        roles.put(code, Role.builder().code(code).name(code).permissions(permissions).build());
    }

    private UserPrincipal globalPrincipal(Set<String> roles, Set<UUID> coopIds) {
        return UserPrincipal.builder()
                .id(userId)
                .username("x")
                .password("")
                .roles(roles)
                .permissions(Set.of("CONTRIBUTION_WRITE", "FINE_WRITE", "LOAN_WRITE"))
                .cooperativeIds(coopIds)
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }
}
