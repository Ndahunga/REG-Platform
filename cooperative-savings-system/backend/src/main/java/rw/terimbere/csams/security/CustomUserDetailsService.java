package rw.terimbere.csams.security;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Permission;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final CooperativeMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return toPrincipal(user);
    }

    @Transactional(readOnly = true)
    public UserPrincipal loadPrincipalById(UUID userId) {
        User user = userRepository
                .findWithRolesById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));
        return toPrincipal(user);
    }

    public UserPrincipal toPrincipal(User user) {
        Set<String> roles = user.getRoleCodes();
        Set<String> permissions = extractPermissions(user.getRoles());
        Set<UUID> cooperativeIds = membershipRepository
                .findByUserIdAndMembershipStatus(user.getId(), "ACTIVE")
                .stream()
                .map(CooperativeMembership::getCooperativeId)
                .collect(Collectors.toSet());

        boolean locked = user.isEffectivelyLocked(java.time.Instant.now())
                || user.getAccountStatus() == AccountStatus.LOCKED;
        boolean enabled = user.getAccountStatus() == AccountStatus.ACTIVE && !user.isDeleted();

        return UserPrincipal.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .roles(new HashSet<>(roles))
                .permissions(permissions)
                .cooperativeIds(cooperativeIds)
                .accountNonLocked(!locked)
                .enabled(enabled)
                .build();
    }

    public static Set<String> extractPermissions(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return roles.stream()
                .filter(r -> r.getPermissions() != null)
                .flatMap(r -> r.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet());
    }

    public List<UUID> activeCooperativeIds(UUID userId) {
        return membershipRepository.findByUserIdAndMembershipStatus(userId, "ACTIVE").stream()
                .map(CooperativeMembership::getCooperativeId)
                .toList();
    }
}
