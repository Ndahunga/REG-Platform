package rw.terimbere.csams.shared.erasure;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The shape of the database as the database itself describes it: every table, which columns may be empty, and every
 * reference from one row to another. Read once and kept, so a table added later is covered without touching this code.
 *
 * <p>A reference is a column that points at another table's {@code id}: a declared foreign key, or one of the few
 * columns that point at a row of any table without a foreign key ({@link #SOFT_REFERENCES}).
 */
public final class DataGraph {

    /** A column of {@code child} that holds the {@code id} of a row of {@code parent} ({@code null}: of any table). */
    public record Ref(String child, String column, String parent, boolean nullable) {}

    /**
     * Columns that hold the id of a row of any table, with no foreign key. When that row goes, the ones marked
     * {@code false} go with it (a ledger line or an approval step about it); the ones marked {@code true} are only
     * emptied (an import row that recorded what it created, a duplicate pointer).
     */
    static final Map<String, Boolean> SOFT_REFERENCES = Map.ofEntries(
            Map.entry("financial_ledger.source_entity_id", false),
            Map.entry("approval_events.entity_id", false),
            Map.entry("notifications.entity_id", false),
            Map.entry("historical_import_rows.resulting_entity_id", true),
            Map.entry("historical_ledger_rows.ledger_entry_id", true),
            Map.entry("historical_ledger_rows.operational_id", true),
            Map.entry("historical_ledger_rows.contribution_id", true),
            Map.entry("payment_receipt_extractions.duplicate_of_payment_id", true),
            Map.entry("share_valuation_snapshots.source_entity_id", true),
            Map.entry("rotation_payout_offsets.rotation_period_id", true));

    private final Map<String, Map<String, Boolean>> columns;
    private final List<Ref> refs;

    private DataGraph(Map<String, Map<String, Boolean>> columns, List<Ref> refs) {
        this.columns = columns;
        this.refs = refs;
    }

    public static DataGraph read(DataSource dataSource) {
        Map<String, Map<String, Boolean>> columns = new LinkedHashMap<>();
        List<Ref> refs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String schema = connection.getSchema();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(connection.getCatalog(), schema, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!table.startsWith("flyway_")) {
                        tables.add(table);
                    }
                }
            }
            for (String table : tables) {
                Map<String, Boolean> cols = new LinkedHashMap<>();
                try (ResultSet rs = meta.getColumns(connection.getCatalog(), schema, name(meta, table), "%")) {
                    while (rs.next()) {
                        cols.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    }
                }
                columns.put(table, cols);
            }
            Set<String> seen = new LinkedHashSet<>();
            if ("PostgreSQL".equalsIgnoreCase(meta.getDatabaseProductName())) {
                // Read straight from the catalogue: every foreign key, including those that point at a (id, scheme) key.
                String sql = "select cl.relname, a.attname, pcl.relname, pa.attname from pg_constraint c"
                        + " join pg_class cl on cl.oid = c.conrelid join pg_class pcl on pcl.oid = c.confrelid"
                        + " join pg_namespace n on n.oid = cl.relnamespace"
                        + " cross join lateral unnest(c.conkey, c.confkey) as k(ck, pk)"
                        + " join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.ck"
                        + " join pg_attribute pa on pa.attrelid = c.confrelid and pa.attnum = k.pk"
                        + " where c.contype = 'f' and n.nspname = current_schema()";
                try (var st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        String table = rs.getString(1).toLowerCase(Locale.ROOT);
                        String column = rs.getString(2).toLowerCase(Locale.ROOT);
                        String parent = rs.getString(3).toLowerCase(Locale.ROOT);
                        if ("id".equalsIgnoreCase(rs.getString(4)) && columns.containsKey(table)
                                && seen.add(table + "." + column + ">" + parent)) {
                            refs.add(new Ref(table, column, parent, Boolean.TRUE.equals(columns.get(table).get(column))));
                        }
                    }
                }
                tables = List.of();
            }
            for (String table : tables) {
                try (ResultSet rs = meta.getImportedKeys(connection.getCatalog(), schema, name(meta, table))) {
                    while (rs.next()) {
                        String parent = rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                        String parentColumn = rs.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                        String column = rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                        // Only the part of a key that names a row: (cooperative_id, x_id) -> (cooperative_id, id) is x_id -> id.
                        if (!"id".equals(parentColumn) || !seen.add(table + "." + column + ">" + parent)) {
                            continue;
                        }
                        refs.add(new Ref(table, column, parent, Boolean.TRUE.equals(columns.get(table).get(column))));
                    }
                }
            }
            for (Map.Entry<String, Boolean> soft : SOFT_REFERENCES.entrySet()) {
                String[] parts = soft.getKey().split("\\.");
                if (columns.containsKey(parts[0]) && columns.get(parts[0]).containsKey(parts[1])) {
                    refs.add(new Ref(parts[0], parts[1], null, soft.getValue()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the database structure", e);
        }
        return new DataGraph(columns, List.copyOf(refs));
    }

    private static String name(DatabaseMetaData meta, String table) throws SQLException {
        return meta.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
    }

    public Set<String> tables() {
        return columns.keySet();
    }

    public boolean has(String table, String column) {
        Map<String, Boolean> cols = columns.get(table);
        return cols != null && cols.containsKey(column);
    }

    public boolean nullable(String table, String column) {
        Map<String, Boolean> cols = columns.get(table);
        return cols != null && Boolean.TRUE.equals(cols.get(column));
    }

    public List<Ref> refs() {
        return refs;
    }

    /** The references that point at {@code users}: who a row is about, or who did something. */
    public List<Ref> refsTo(String parent) {
        List<Ref> out = new ArrayList<>();
        for (Ref r : refs) {
            if (parent.equals(r.parent())) {
                out.add(r);
            }
        }
        return out;
    }

    /** Tables ordered so that every table comes before the tables it refers to (rows are deleted in this order). */
    public List<String> childrenFirst(Set<String> involved) {
        Map<String, Set<String>> parentsOf = new HashMap<>();
        for (String t : involved) {
            parentsOf.put(t, new LinkedHashSet<>());
        }
        for (Ref r : refs) {
            if (r.parent() != null && !r.child().equals(r.parent()) && involved.contains(r.child()) && involved.contains(r.parent())) {
                parentsOf.get(r.child()).add(r.parent());
            }
        }
        // A table can go once nothing still to be deleted refers to it.
        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(involved);
        while (!remaining.isEmpty()) {
            String next = null;
            for (String candidate : remaining) {
                boolean referenced = false;
                for (String other : remaining) {
                    if (!other.equals(candidate) && parentsOf.get(other).contains(candidate)) {
                        referenced = true;
                        break;
                    }
                }
                if (!referenced) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("Circular references between " + remaining);
            }
            order.add(next);
            remaining.remove(next);
        }
        return order;
    }
}
