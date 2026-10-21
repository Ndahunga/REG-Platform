package rw.terimbere.csams.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Single, fail-closed parser for the cooperative segment of an API path. Used by both
 * {@link CooperativeScopeFilter} and {@link SubscriptionWriteInterceptor} so they can never disagree.
 *
 * <p>The raw (still percent-encoded) request URI is parsed. Spring MVC decodes path segments before it
 * binds {@code {cooperativeId}}, so a segment that is not a plain canonical UUID (for example one with
 * {@code %31} or {@code %2d}) could still reach a controller as a valid id. Such segments are therefore
 * classified as {@link Kind#INVALID} instead of being skipped.
 */
public final class CooperativePath {

    /** What the request path says about the cooperative it targets. */
    public enum Kind {
        /** Not under {@code /api/v1/cooperatives}: auth, notifications, files, platform, ... */
        NOT_COOPERATIVE_SCOPED,
        /** {@code /api/v1/cooperatives}, {@code /api/v1/cooperatives/} or {@code /api/v1/cooperatives/mine}. */
        COOPERATIVE_COLLECTION,
        /** {@code /api/v1/cooperatives/{canonical-uuid}[/...]}. */
        COOPERATIVE,
        /** A cooperative segment that is not a canonical UUID and not a known literal. Must be rejected. */
        INVALID
    }

    public record Result(Kind kind, UUID cooperativeId) {}

    private static final String PREFIX = "/api/v1/cooperatives";
    private static final String MINE = "mine";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Result NOT_SCOPED = new Result(Kind.NOT_COOPERATIVE_SCOPED, null);
    private static final Result COLLECTION = new Result(Kind.COOPERATIVE_COLLECTION, null);
    private static final Result INVALID = new Result(Kind.INVALID, null);

    private CooperativePath() {}

    /** Request path without context path and query string, still percent-encoded as received. */
    public static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri == null || uri.isEmpty() ? "/" : uri;
    }

    public static Result parse(HttpServletRequest request) {
        return parse(pathOf(request));
    }

    public static Result parse(String path) {
        if (path == null) {
            return NOT_SCOPED;
        }
        int cut = firstIndexOfAny(path, '?', '#');
        String clean = cut >= 0 ? path.substring(0, cut) : path;

        if (!clean.equals(PREFIX) && !clean.startsWith(PREFIX + "/")) {
            return NOT_SCOPED;
        }
        String rest = clean.substring(PREFIX.length());
        if (rest.isEmpty() || rest.equals("/")) {
            return COLLECTION;
        }

        String afterSlash = rest.substring(1);
        int end = afterSlash.indexOf('/');
        String segment = end < 0 ? afterSlash : afterSlash.substring(0, end);
        String remainder = end < 0 ? "" : afterSlash.substring(end);

        if (segment.equals(MINE) && (remainder.isEmpty() || remainder.equals("/"))) {
            return COLLECTION;
        }
        if (CANONICAL_UUID.matcher(segment).matches()) {
            return new Result(Kind.COOPERATIVE, UUID.fromString(segment));
        }
        return INVALID;
    }

    private static int firstIndexOfAny(String value, char a, char b) {
        int first = -1;
        for (char c : new char[] {a, b}) {
            int idx = value.indexOf(c);
            if (idx >= 0 && (first < 0 || idx < first)) {
                first = idx;
            }
        }
        return first;
    }
}
