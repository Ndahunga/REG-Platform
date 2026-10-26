package rw.terimbere.csams.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single-container deployments (staging) serve the built frontend from the backend. A browser refresh on a client-side
 * route such as /rotation must return index.html; API, actuator and real files are left alone. Off unless
 * {@code app.spa.enabled=true}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.spa.enabled", havingValue = "true")
public class SpaFallbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean page = "GET".equals(request.getMethod())
                && !path.startsWith("/api/")
                && !path.startsWith("/actuator")
                && !path.startsWith("/v3/")
                && !path.startsWith("/swagger")
                && !path.substring(path.lastIndexOf('/') + 1).contains(".");
        if (page && !"/index.html".equals(path)) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
