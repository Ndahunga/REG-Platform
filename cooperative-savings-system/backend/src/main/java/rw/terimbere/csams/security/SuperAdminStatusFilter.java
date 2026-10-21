package rw.terimbere.csams.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * Makes deactivating a system SUPER_ADMIN take effect on the very next request instead of when the
 * access token expires.
 *
 * <p>Roles are read from the JWT, so a deactivated administrator would otherwise keep full access for the
 * rest of the token's lifetime. For requests that carry the global {@code SUPER_ADMIN} role only, this
 * filter re-checks that the user still exists, is not deleted and is {@code ACTIVE}; if not, the
 * authentication is dropped so the request continues as anonymous (protected endpoints answer 401).
 * Requests from every other user never reach the database here.
 *
 * <p>Deliberately not a Spring bean, for the same reason as {@link CooperativeScopeFilter}: it is added to
 * the security chain right after {@link JwtAuthenticationFilter} and must not also be auto-registered as a
 * servlet filter.
 */
public class SuperAdminStatusFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public SuperAdminStatusFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !userRepository.existsByIdAndDeletedFalseAndAccountStatus(
                        principal.getId(), AccountStatus.ACTIVE)) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}
