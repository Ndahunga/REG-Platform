package rw.terimbere.csams.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.terimbere.csams.shared.common.dto.ErrorResponse;
import rw.terimbere.csams.shared.utilities.RequestIdFilter;

/**
 * Re-scopes the authenticated principal to the cooperative named in the request path
 * ({@code /api/v1/cooperatives/{uuid}/...}), failing closed when that segment cannot be trusted.
 *
 * <p>The JWT carries global roles and permissions. For a cooperative-scoped request those are replaced
 * with the roles and permissions of the caller's <em>current</em> membership in that cooperative, so every
 * downstream check ({@code @PreAuthorize("hasAuthority(..)")}, {@code principal.hasAuthority(..)},
 * {@link CooperativeOfficerRoles}) is evaluated against the target cooperative. Super Admins are untouched.
 *
 * <p>Path classification is delegated to {@link CooperativePath}. A cooperative segment that is not a
 * plain canonical UUID (for example one containing percent-encoded characters) is rejected with 400
 * before the request can reach a controller, instead of being skipped with global authorities intact.
 *
 * <p>Deliberately not a Spring bean: it is added to the security filter chain right after
 * {@link JwtAuthenticationFilter} so it cannot also be auto-registered in the servlet filter chain.
 */
public class CooperativeScopeFilter extends OncePerRequestFilter {

    static final String INVALID_PATH_MESSAGE = "Invalid cooperative identifier in request path";

    private final CooperativeAccessResolver accessResolver;
    private final ObjectMapper objectMapper;

    public CooperativeScopeFilter(CooperativeAccessResolver accessResolver, ObjectMapper objectMapper) {
        this.accessResolver = accessResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CooperativePath.Result path = CooperativePath.parse(request);

        if (path.kind() == CooperativePath.Kind.INVALID) {
            rejectInvalidPath(request, response);
            return;
        }

        if (path.kind() == CooperativePath.Kind.COOPERATIVE) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
                UserPrincipal scoped = accessResolver.scope(principal, path.cooperativeId());
                if (scoped != principal) {
                    UsernamePasswordAuthenticationToken scopedAuthentication =
                            new UsernamePasswordAuthenticationToken(scoped, null, scoped.getAuthorities());
                    scopedAuthentication.setDetails(authentication.getDetails());
                    SecurityContextHolder.getContext().setAuthentication(scopedAuthentication);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void rejectInvalidPath(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpServletResponse.SC_BAD_REQUEST)
                .error("Bad Request")
                .message(INVALID_PATH_MESSAGE)
                .requestId(MDC.get(RequestIdFilter.MDC_REQUEST_ID))
                .build();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
