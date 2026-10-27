package rw.terimbere.csams.shared.erasure;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes a user account once nothing is left that needs it: it belongs to no scheme, is not a platform administrator,
 * and no remaining row refers to it. Rows that only run the account (roles, sign-in tokens, notifications) go with it.
 */
@Component
@RequiredArgsConstructor
public class AccountCleanup {

    private static final Set<String> ACCOUNT_ONLY =
            Set.of("user_roles", "refresh_tokens", "password_reset_tokens", "notifications", "cooperative_memberships");

    private final JdbcTemplate jdbc;
    private final DataEraser eraser;

    /** Deletes the account when nothing needs it. Returns whether it was deleted. */
    public boolean deleteIfUnused(UUID userId) {
        Long admin = jdbc.queryForObject(
                "select count(*) from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = ? and r.code = 'SUPER_ADMIN'",
                Long.class, userId);
        if (admin != null && admin > 0) {
            return false;
        }
        Long memberships = jdbc.queryForObject("select count(*) from cooperative_memberships where user_id = ?", Long.class, userId);
        if (memberships != null && memberships > 0) {
            return false;
        }
        for (DataGraph.Ref ref : eraser.graph().refsTo("users")) {
            if (ACCOUNT_ONLY.contains(ref.child())) {
                continue;
            }
            Long n = jdbc.queryForObject("select count(*) from " + ref.child() + " where " + ref.column() + " = ?", Long.class, userId);
            if (n != null && n > 0) {
                return false;
            }
        }
        for (String table : List.of("notifications", "refresh_tokens", "password_reset_tokens", "user_roles")) {
            if (eraser.graph().has(table, "user_id")) {
                jdbc.update("delete from " + table + " where user_id = ?", userId);
            }
        }
        return jdbc.update("delete from users where id = ?", userId) == 1;
    }
}
