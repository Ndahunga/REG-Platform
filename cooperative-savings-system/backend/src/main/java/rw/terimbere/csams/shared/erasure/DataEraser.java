package rw.terimbere.csams.shared.erasure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes rows together with every row that cannot exist without them, in an order the database accepts, inside the
 * caller's transaction (a failure leaves everything as it was).
 *
 * <p>Starting from the rows asked for, it follows every reference to them: a row that must point at a deleted row is
 * deleted too; a row that may be left pointing at nothing has that pointer emptied instead. {@link #plan} works this out
 * without changing anything, so the caller can show it, or refuse when it reaches rows it must not touch.
 */
@Component
@RequiredArgsConstructor
public class DataEraser {

    private static final int CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private volatile DataGraph graph;

    public DataGraph graph() {
        DataGraph g = graph;
        if (g == null) {
            g = DataGraph.read(dataSource);
            graph = g;
        }
        return g;
    }

    /** What would go: rows per table, and the pointers emptied in rows that stay. */
    public static final class Plan {
        final Map<String, Set<UUID>> rows = new LinkedHashMap<>();
        final Map<String, Map<String, Set<UUID>>> emptied = new LinkedHashMap<>();

        public Map<String, Set<UUID>> rows() {
            return rows;
        }

        public Set<UUID> rows(String table) {
            return rows.getOrDefault(table, Set.of());
        }

        public boolean deletes(String table, UUID id) {
            return rows(table).contains(id);
        }

        /** Rows that go, by table (only tables with at least one), sorted by table name. */
        public Map<String, Integer> counts() {
            Map<String, Integer> out = new TreeMap<>();
            rows.forEach((t, ids) -> {
                if (!ids.isEmpty()) {
                    out.put(t, ids.size());
                }
            });
            return out;
        }

        public Set<UUID> allIds() {
            Set<UUID> all = new HashSet<>();
            rows.values().forEach(all::addAll);
            return all;
        }

        public void add(String table, Collection<UUID> ids) {
            rows.computeIfAbsent(table, k -> new LinkedHashSet<>()).addAll(ids);
        }

        /** Leaves a row in place but empties one of its pointers. */
        public void empty(String table, String column, Collection<UUID> ids) {
            emptied.computeIfAbsent(table, k -> new LinkedHashMap<>())
                    .computeIfAbsent(column, k -> new LinkedHashSet<>())
                    .addAll(ids);
        }
    }

    /** Every row that has to go with the seeds (and the pointers to empty), without changing anything. */
    public Plan plan(Map<String, ? extends Collection<UUID>> seeds) {
        Plan plan = new Plan();
        seeds.forEach((t, ids) -> plan.add(t, ids));
        DataGraph g = graph();
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<UUID> everything = plan.allIds();
            for (DataGraph.Ref ref : g.refs()) {
                Collection<UUID> targets = ref.parent() == null ? everything : plan.rows(ref.parent());
                if (targets.isEmpty() || !g.has(ref.child(), "id")) {
                    continue;
                }
                List<UUID> found = idsWhereIn(ref.child(), ref.column(), targets);
                List<UUID> fresh = new ArrayList<>();
                for (UUID id : found) {
                    if (!plan.deletes(ref.child(), id)) {
                        fresh.add(id);
                    }
                }
                if (fresh.isEmpty()) {
                    continue;
                }
                if (ref.nullable() && !ref.child().equals(ref.parent())) {
                    plan.empty(ref.child(), ref.column(), fresh);
                } else {
                    plan.add(ref.child(), fresh);
                    changed = true;
                }
            }
        }
        return plan;
    }

    /** Carries the plan out: empties pointers, then deletes rows children first. Returns rows deleted per table. */
    public Map<String, Integer> execute(Plan plan) {
        DataGraph g = graph();
        for (Map.Entry<String, Map<String, Set<UUID>>> table : plan.emptied.entrySet()) {
            for (Map.Entry<String, Set<UUID>> column : table.getValue().entrySet()) {
                List<UUID> keep = column.getValue().stream().filter(id -> !plan.deletes(table.getKey(), id)).toList();
                updateNull(table.getKey(), column.getKey(), keep);
            }
        }
        // A row that points at another row of the same table (a reversal) lets go of it first.
        for (DataGraph.Ref ref : g.refs()) {
            if (ref.child().equals(ref.parent()) && ref.nullable() && !plan.rows(ref.child()).isEmpty()) {
                updateNull(ref.child(), ref.column(), plan.rows(ref.child()));
            }
        }
        Set<String> involved = new LinkedHashSet<>();
        plan.rows.forEach((t, ids) -> {
            if (!ids.isEmpty()) {
                involved.add(t);
            }
        });
        Map<String, Integer> deleted = new TreeMap<>();
        for (String table : g.childrenFirst(involved)) {
            int n = 0;
            for (List<UUID> chunk : chunks(plan.rows(table))) {
                n += jdbc.update("delete from " + table + " where id in (" + marks(chunk.size()) + ")", chunk.toArray());
            }
            deleted.put(table, n);
        }
        return deleted;
    }

    /** Ids of rows of a table that belong to a scheme. */
    public List<UUID> idsOfScheme(String table, UUID cooperativeId) {
        return jdbc.queryForList("select id from " + table + " where cooperative_id = ?", UUID.class, cooperativeId);
    }

    /** True when every row in the plan that has a scheme belongs to this one. */
    public List<String> rowsOutsideScheme(Plan plan, UUID cooperativeId) {
        DataGraph g = graph();
        List<String> outside = new ArrayList<>();
        plan.rows.forEach((table, ids) -> {
            if (ids.isEmpty() || !g.has(table, "cooperative_id")) {
                return;
            }
            for (List<UUID> chunk : chunks(ids)) {
                List<Object> args = new ArrayList<>(chunk);
                args.add(cooperativeId);
                Long n = jdbc.queryForObject("select count(*) from " + table + " where id in (" + marks(chunk.size())
                        + ") and (cooperative_id is null or cooperative_id <> ?)", Long.class, args.toArray());
                if (n != null && n > 0) {
                    outside.add(table);
                    return;
                }
            }
        });
        return outside;
    }

    private List<UUID> idsWhereIn(String table, String column, Collection<UUID> values) {
        List<UUID> out = new ArrayList<>();
        for (List<UUID> chunk : chunks(values)) {
            out.addAll(jdbc.queryForList("select id from " + table + " where " + column + " in (" + marks(chunk.size()) + ")",
                    UUID.class, chunk.toArray()));
        }
        return out;
    }

    private void updateNull(String table, String column, Collection<UUID> ids) {
        for (List<UUID> chunk : chunks(ids)) {
            jdbc.update("update " + table + " set " + column + " = null where id in (" + marks(chunk.size()) + ")", chunk.toArray());
        }
    }

    static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        List<UUID> current = new ArrayList<>();
        for (UUID id : ids) {
            current.add(id);
            if (current.size() == CHUNK) {
                out.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
