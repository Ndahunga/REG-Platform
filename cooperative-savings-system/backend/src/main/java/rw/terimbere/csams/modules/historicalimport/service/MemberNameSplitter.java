package rw.terimbere.csams.modules.historicalimport.service;

/**
 * Deterministic split of a single "Full Name" cell into the platform's firstName / lastName pair.
 *
 * <ul>
 *   <li>repeated whitespace is collapsed and the name trimmed;</li>
 *   <li>the first word becomes {@code firstName}, the remaining words (single-spaced) become {@code lastName};</li>
 *   <li>a one-word name keeps an <em>empty</em> {@code lastName} (the column is NOT NULL but has no
 *       minimum length). No placeholder surname is invented, so reports show just the one name
 *       ({@code User.getFullName()} trims the result).</li>
 * </ul>
 */
final class MemberNameSplitter {

    record Name(String firstName, String lastName) {}

    private MemberNameSplitter() {}

    static Name split(String fullName) {
        String normalized = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return new Name("", "");
        }
        int space = normalized.indexOf(' ');
        if (space < 0) {
            return new Name(normalized, "");
        }
        return new Name(normalized.substring(0, space), normalized.substring(space + 1));
    }
}
