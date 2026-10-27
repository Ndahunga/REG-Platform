package rw.terimbere.csams.modules.cooperative.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.erasure.AccountCleanup;
import rw.terimbere.csams.shared.erasure.DataEraser;
import rw.terimbere.csams.shared.erasure.DataGraph;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Deletes a Saving Scheme for good (platform administrators only), beyond suspending or archiving it: every row of the
 * scheme goes, in one transaction, and member accounts that belong to no other scheme go with it. The audit trail stays,
 * and a deletion record keeps who deleted the scheme, when, why and how much was removed.
 */
@Service
@RequiredArgsConstructor
public class SchemeDeletionService {

    /** Kept when a scheme is deleted: the trail of what happened, and the deletion records themselves. */
    static final Set<String> KEPT = Set.of("audit_logs", "data_deletion_records");

    private final JdbcTemplate jdbc;
    private final DataEraser eraser;
    private final AccountCleanup accountCleanup;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public record Preview(
            UUID schemeId,
            String schemeName,
            int members,
            /** Member accounts that belong to no other scheme and would be removed too. */
            int accountsRemoved,
            long rowsTotal,
            Map<String, Integer> rowsByTable) {}

    public record DeleteRequest(String confirmName, String reason) {}

    public record Result(UUID schemeId, String schemeName, long rowsDeleted, int accountsDeleted) {}

    @Transactional(readOnly = true)
    public Preview preview(UUID cooperativeId) {
        requireAdmin();
        String name = requireName(cooperativeId);
        DataEraser.Plan plan = plan(cooperativeId);
        List<UUID> members = members(cooperativeId);
        int accounts = 0;
        for (UUID member : members) {
            Long others = jdbc.queryForObject("select count(*) from cooperative_memberships where user_id = ? and cooperative_id <> ?",
                    Long.class, member, cooperativeId);
            if (others == null || others == 0) {
                accounts++;
            }
        }
        Map<String, Integer> counts = new TreeMap<>(plan.counts());
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        return new Preview(cooperativeId, name, members.size(), accounts, total, counts);
    }

    @Transactional
    public Result delete(UUID cooperativeId, DeleteRequest request, HttpServletRequest http) {
        UserPrincipal actor = requireAdmin();
        String name = requireName(cooperativeId);
        String typed = request == null || request.confirmName() == null ? "" : request.confirmName().trim();
        if (!typed.toLowerCase(Locale.ROOT).equals(name.trim().toLowerCase(Locale.ROOT))) {
            throw new ConflictException("CONFIRM_NAME_MISMATCH", "Type the scheme's name exactly to confirm the deletion");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 10) {
            throw new ConflictException("REASON_REQUIRED", "Explain why the scheme is deleted (at least 10 characters)");
        }
        // One deletion at a time for a scheme; anything else writing to it waits.
        jdbc.queryForList("select id from cooperatives where id = ? for update", UUID.class, cooperativeId);
        List<UUID> members = members(cooperativeId);
        DataEraser.Plan plan = plan(cooperativeId);
        List<String> outside = eraser.rowsOutsideScheme(plan, cooperativeId);
        if (!outside.isEmpty()) {
            throw new ConflictException("SCHEME_SHARES_RECORDS",
                    "Records of another scheme depend on this one (" + String.join(", ", outside) + "); nothing was deleted");
        }
        Map<String, Integer> deleted = new LinkedHashMap<>(eraser.execute(plan));
        DataGraph g = eraser.graph();
        // Tables keyed by the scheme itself (no row id) go last, then the scheme.
        for (String table : g.tables()) {
            if (!KEPT.contains(table) && g.has(table, "cooperative_id") && !g.has(table, "id")) {
                int n = jdbc.update("delete from " + table + " where cooperative_id = ?", cooperativeId);
                if (n > 0) {
                    deleted.put(table, n);
                }
            }
        }
        deleted.put("cooperatives", jdbc.update("delete from cooperatives where id = ?", cooperativeId));
        int accounts = 0;
        for (UUID member : members) {
            if (accountCleanup.deleteIfUnused(member)) {
                accounts++;
            }
        }
        long total = deleted.values().stream().mapToLong(Integer::longValue).sum();
        String counts = json(new TreeMap<>(deleted));
        jdbc.update("insert into data_deletion_records (id, kind, scheme_id, scheme_name, reason, rows_deleted, accounts_deleted, deleted_by, deleted_at)"
                        + " values (?, 'SCHEME', ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), cooperativeId, name, cut(reason), counts, accounts, actor.getId());
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.SCHEME_DELETE,
                "Cooperative",
                cooperativeId,
                json(Map.of("name", name)),
                json(Map.of("rowsDeleted", total, "accountsDeleted", accounts)),
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return new Result(cooperativeId, name, total, accounts);
    }

    /** Every row of the scheme that has an id, and everything that refers to those rows. */
    private DataEraser.Plan plan(UUID cooperativeId) {
        DataGraph g = eraser.graph();
        Map<String, List<UUID>> seeds = new LinkedHashMap<>();
        for (String table : g.tables()) {
            if (!KEPT.contains(table) && g.has(table, "cooperative_id") && g.has(table, "id")) {
                List<UUID> ids = eraser.idsOfScheme(table, cooperativeId);
                if (!ids.isEmpty()) {
                    seeds.put(table, ids);
                }
            }
        }
        return eraser.plan(seeds);
    }

    private List<UUID> members(UUID cooperativeId) {
        return new ArrayList<>(jdbc.queryForList(
                "select user_id from cooperative_memberships where cooperative_id = ?", UUID.class, cooperativeId));
    }

    private String requireName(UUID cooperativeId) {
        List<String> names = jdbc.queryForList("select name from cooperatives where id = ?", String.class, cooperativeId);
        if (names.isEmpty()) {
            throw new ResourceNotFoundException("Cooperative", cooperativeId);
        }
        return names.get(0) == null ? "" : names.get(0);
    }

    private UserPrincipal requireAdmin() {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("Only a platform administrator can delete a scheme");
        }
        return principal;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String cut(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
