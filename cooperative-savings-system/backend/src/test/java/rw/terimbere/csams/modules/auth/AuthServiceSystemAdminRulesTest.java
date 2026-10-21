package rw.terimbere.csams.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.auth.dto.AuthResult;
import rw.terimbere.csams.modules.auth.dto.BootstrapAdminRequest;
import rw.terimbere.csams.modules.auth.dto.SignupRequest;
import rw.terimbere.csams.modules.auth.repository.RefreshTokenRepository;
import rw.terimbere.csams.modules.auth.service.AuthService;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.JwtService;
import rw.terimbere.csams.shared.exceptions.ConflictException;

/**
 * The empty-installation cases that cannot be reproduced against the shared integration-test database:
 * the legacy public signup must never grant SUPER_ADMIN, and bootstrap must still create exactly the first
 * one (under a role-row lock), then refuse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSystemAdminRulesTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private CooperativeMembershipRepository membershipRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationFacade notificationFacade;

    @InjectMocks
    private AuthService authService;

    private Role superAdminRole;
    private Role memberRole;

    @BeforeEach
    void setUp() {
        superAdminRole = Role.builder().code("SUPER_ADMIN").name("Super").build();
        memberRole = Role.builder().code("MEMBER").name("Member").build();
        when(roleRepository.findByCode("MEMBER")).thenReturn(Optional.of(memberRole));
        when(roleRepository.findByCode("SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(roleRepository.findByCodeForUpdate("SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(membershipRepository.findByUserIdAndMembershipStatus(any(), anyString())).thenReturn(List.of());
        when(jwtService.generateAccessToken(any(), anyString(), any(), any(), any())).thenReturn("access");
        when(jwtService.generateRefreshToken(any(), anyString())).thenReturn("refresh");
        when(jwtService.extractRefreshExpiration(anyString())).thenReturn(Instant.now().plusSeconds(60));
    }

    @Test
    void legacySignup_onEmptyDatabase_neverGrantsSuperAdmin() {
        when(userRepository.countByDeletedFalse()).thenReturn(0L);
        SignupRequest request = SignupRequest.builder()
                .username("firstever")
                .email("first@test.local")
                .password("SignupPass1!")
                .firstName("First")
                .lastName("Ever")
                .build();

        AuthResult result = authService.signup(request, new MockHttpServletRequest());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoleCodes()).containsExactly("MEMBER");
        verify(roleRepository, never()).findByCode("SUPER_ADMIN");
        assertThat(result.getResponse().getUser().getRoles()).doesNotContain("SUPER_ADMIN");
    }

    @Test
    void bootstrap_onEmptyInstallation_createsTheFirstSuperAdminUnderRoleLock() {
        when(userRepository.countByDeletedFalse()).thenReturn(0L);
        when(userRepository.findWithRolesById(any())).thenAnswer(inv -> Optional.empty());
        BootstrapAdminRequest request = BootstrapAdminRequest.builder()
                .username("owner")
                .email("owner@test.local")
                .password("OwnerPass1!")
                .firstName("Sys")
                .lastName("Owner")
                .build();

        try {
            authService.bootstrapAdmin(request, new MockHttpServletRequest());
        } catch (RuntimeException ignoredLookupAfterSave) {
            // currentUser() re-reads the saved user, which this mock does not serve.
        }

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoleCodes()).isEqualTo(Set.of("SUPER_ADMIN"));
        verify(roleRepository).findByCodeForUpdate("SUPER_ADMIN");
    }

    @Test
    void bootstrap_whenAnyUserExists_isRejectedAndCreatesNothing() {
        when(userRepository.countByDeletedFalse()).thenReturn(1L);
        BootstrapAdminRequest request = BootstrapAdminRequest.builder()
                .username("second")
                .email("second@test.local")
                .password("SecondPass1!")
                .firstName("Sec")
                .lastName("Ond")
                .build();

        assertThatThrownBy(() -> authService.bootstrapAdmin(request, new MockHttpServletRequest()))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any(User.class));
    }
}
