package rw.terimbere.csams.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@Builder(toBuilder = true)
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String password;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Set<UUID> cooperativeIds;

    /**
     * When non-null, {@link #roles} and {@link #permissions} were resolved from the caller's membership
     * in exactly this cooperative (see {@link CooperativeAccessResolver}) instead of from global user roles.
     */
    private final UUID scopedCooperativeId;

    private final boolean accountNonLocked;
    private final boolean enabled;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (roles != null) {
            roles.stream()
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }
        if (permissions != null) {
            permissions.stream()
                    .filter(p -> p != null && !p.isBlank())
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }
        return authorities;
    }

    public boolean hasRole(String roleCode) {
        if (roles == null || roleCode == null) {
            return false;
        }
        String normalized = roleCode.startsWith("ROLE_") ? roleCode.substring(5) : roleCode;
        return roles.stream().anyMatch(r -> {
            String code = r.startsWith("ROLE_") ? r.substring(5) : r;
            return code.equalsIgnoreCase(normalized);
        });
    }

    public boolean hasAuthority(String authority) {
        return getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }

    public boolean isMemberOf(UUID cooperativeId) {
        return cooperativeIds != null && cooperativeIds.contains(cooperativeId);
    }

    public Set<String> getPermissionCodes() {
        return permissions == null ? Set.of() : permissions;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public static Set<GrantedAuthority> toAuthorities(Collection<String> roles, Collection<String> permissions) {
        return Stream.concat(
                        roles == null
                                ? Stream.empty()
                                : roles.stream().map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r),
                        permissions == null ? Stream.empty() : permissions.stream())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }

    public List<String> roleCodesWithoutPrefix() {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                .toList();
    }
}
