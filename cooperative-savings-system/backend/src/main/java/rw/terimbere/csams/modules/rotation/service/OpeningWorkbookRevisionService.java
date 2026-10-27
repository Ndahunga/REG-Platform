package rw.terimbere.csams.modules.rotation.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.shared.erasure.DataEraser;
import rw.terimbere.csams.shared.erasure.DataGraph;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * Replaces the rotation and positions workbook with a corrected one (runs in the caller's transaction).
 *
 * <p>What the earlier upload set is taken back: rotated money paid and payouts received before the cutover, amounts owed
 * to members, the rotation cash at the cutover, and the imported cycle with its turns. Contributions still owed and
 * untouched are recreated from the corrected workbook; anything already recorded in the app since (a payment, a turn
 * paid out, a recipient change) is kept and moved to the same month and member of the corrected cycle. When the
 * corrected cycle no longer has that month or that member's turn, the correction is refused and says why.
 */
@Service
@RequiredArgsConstructor
public class OpeningWorkbookRevisionService {

    private final JdbcTemplate jdbc;
    private final DataEraser eraser;

    public record Current(UUID id, int version, UUID cycleId, YearMonth firstMonth) {}

    /** What the earlier cycle held, to move what was recorded on it to the corrected cycle. */
    public record Undone(UUID oldCycleId, Map<UUID, YearMonth> periods, Map<UUID, String> turns) {}

    public Current current(UUID cooperativeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, version_number, cycle_id, first_month_year, first_month_month from opening_workbook_imports"
                        + " where cooperative_id = ? and status = 'CURRENT'", cooperativeId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        YearMonth first = r.get("first_month_year") == null ? null
                : YearMonth.of(((Number) r.get("first_month_year")).intValue(), ((Number) r.get("first_month_month")).intValue());
        return new Current((UUID) r.get("id"), ((Number) r.get("version_number")).intValue(), (UUID) r.get("cycle_id"), first);
    }

    public int nextVersion(UUID cooperativeId) {
        Integer max = jdbc.queryForObject("select max(version_number) from opening_workbook_imports where cooperative_id = ?",
                Integer.class, cooperativeId);
        return max == null ? 1 : max + 1;
    }

    /** Takes back what the earlier upload set, keeping its cycle in place (without its months) until the move. */
    public Undone undo(UUID cooperativeId, Current current, BigDecimal newRotatedUnit) {
        jdbc.update("update member_opening_balances set opening_rotation_paid = 0, opening_rotation_received = 0,"
                + " opening_owed_to_member = 0 where cooperative_id = ?", cooperativeId);
        jdbc.update("delete from rotation_pool_entries where cooperative_id = ? and entry_type = 'OPENING'", cooperativeId);
        if (current.cycleId() == null) {
            return new Undone(null, Map.of(), Map.of());
        }
        Map<UUID, YearMonth> periods = new LinkedHashMap<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "select id, period_year, period_month from rotation_periods where cycle_id = ?", current.cycleId())) {
            periods.put((UUID) p.get("id"),
                    YearMonth.of(((Number) p.get("period_year")).intValue(), ((Number) p.get("period_month")).intValue()));
        }
        Map<UUID, String> turns = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.queryForList(
                "select id, member_user_id, rotation_period_id from rotation_turns where cycle_id = ?", current.cycleId())) {
            turns.put((UUID) t.get("id"), t.get("member_user_id") + "@" + periods.get((UUID) t.get("rotation_period_id")));
        }
        // The months are released so the corrected cycle can take them.
        jdbc.update("update rotation_periods set slot_key = null where cycle_id = ?", current.cycleId());

        // Contributions still owed and untouched are recreated by the corrected upload (with its split).
        if (!periods.isEmpty()) {
            List<UUID> untouched = untouchedObligations(cooperativeId, periods.values(), current.firstMonth());
            if (!untouched.isEmpty()) {
                eraser.execute(eraser.plan(Map.of("contributions", untouched)));
            }
        }
        // The split set by the earlier upload goes if nothing uses it any more; if it is in use it must not change.
        if (current.firstMonth() != null) {
            List<Map<String, Object>> configs = jdbc.queryForList(
                    "select id, rotating_unit_amount from rotation_configurations where cooperative_id = ? and effective_year = ? and effective_month = ?",
                    cooperativeId, current.firstMonth().getYear(), current.firstMonth().getMonthValue());
            for (Map<String, Object> c : configs) {
                UUID config = (UUID) c.get("id");
                Long used = jdbc.queryForObject("select count(*) from contributions where rotation_configuration_id = ?", Long.class, config);
                if (used == null || used == 0) {
                    jdbc.update("delete from rotation_configurations where id = ?", config);
                } else {
                    BigDecimal unit = c.get("rotating_unit_amount") == null ? null : new BigDecimal(c.get("rotating_unit_amount").toString());
                    boolean same = (unit == null && newRotatedUnit == null)
                            || (unit != null && newRotatedUnit != null && unit.compareTo(newRotatedUnit) == 0);
                    if (!same) {
                        throw new BusinessException("ROTATION_SPLIT_IN_USE",
                                "Contributions already paid in the app use the rotated amount of the earlier upload; it cannot change now."
                                        + " Keep the same rotated amount per share.");
                    }
                }
            }
        }
        return new Undone(current.cycleId(), periods, turns);
    }

    /** Moves what was recorded on the earlier cycle to the corrected one, then deletes the earlier cycle. */
    public void moveToCorrectedCycle(UUID cooperativeId, Undone undone, UUID newCycleId) {
        if (undone.oldCycleId() == null) {
            return;
        }
        Map<YearMonth, UUID> newPeriods = new HashMap<>();
        Map<UUID, YearMonth> newPeriodMonths = new HashMap<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "select id, period_year, period_month from rotation_periods where cycle_id = ?", newCycleId)) {
            YearMonth ym = YearMonth.of(((Number) p.get("period_year")).intValue(), ((Number) p.get("period_month")).intValue());
            newPeriods.put(ym, (UUID) p.get("id"));
            newPeriodMonths.put((UUID) p.get("id"), ym);
        }
        Map<String, UUID> newTurns = new HashMap<>();
        if (newCycleId != null) {
            for (Map<String, Object> t : jdbc.queryForList(
                    "select id, member_user_id, rotation_period_id from rotation_turns where cycle_id = ?", newCycleId)) {
                newTurns.put(t.get("member_user_id") + "@" + newPeriodMonths.get((UUID) t.get("rotation_period_id")), (UUID) t.get("id"));
            }
        }
        DataGraph g = eraser.graph();
        List<String> problems = new ArrayList<>();
        for (DataGraph.Ref ref : g.refs()) {
            boolean toPeriods = "rotation_periods".equals(ref.parent())
                    || (ref.parent() == null && "rotation_period_id".equals(ref.column()));
            boolean toTurns = "rotation_turns".equals(ref.parent());
            if ((!toPeriods && !toTurns) || Set.of("rotation_turns", "rotation_periods").contains(ref.child())) {
                continue;
            }
            Map<UUID, ?> olds = toPeriods ? undone.periods() : undone.turns();
            for (Map.Entry<UUID, ?> old : olds.entrySet()) {
                List<UUID> rows = jdbc.queryForList(
                        "select id from " + ref.child() + " where " + ref.column() + " = ?", UUID.class, old.getKey());
                if (rows.isEmpty()) {
                    continue;
                }
                UUID target = toPeriods ? newPeriods.get((YearMonth) old.getValue()) : newTurns.get((String) old.getValue());
                if (target == null) {
                    problems.add(toPeriods
                            ? "month " + old.getValue() + " (" + ref.child() + ")"
                            : "the turn of " + username(((String) old.getValue()).split("@")[0]) + " in "
                                    + ((String) old.getValue()).split("@")[1] + " (" + ref.child() + ")");
                    continue;
                }
                for (UUID row : rows) {
                    jdbc.update("update " + ref.child() + " set " + ref.column() + " = ? where id = ?", target, row);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException("ROTATION_RECORDS_NEED_THEIR_TURN",
                    "Records made in the app since the earlier upload belong to " + String.join("; ", problems)
                            + ", which the corrected workbook no longer has. Keep them in it.");
        }
        DataEraser.Plan plan = eraser.plan(Map.of("rotation_cycles", List.of(undone.oldCycleId())));
        Set<String> touched = new java.util.LinkedHashSet<>(plan.counts().keySet());
        touched.removeAll(Set.of("rotation_cycles", "rotation_periods", "rotation_turns"));
        if (!touched.isEmpty()) {
            throw new BusinessException("ROTATION_RECORDS_NEED_THEIR_TURN",
                    "The earlier cycle still has records (" + String.join(", ", touched) + ")");
        }
        eraser.execute(plan);
    }

    private List<UUID> untouchedObligations(UUID cooperativeId, java.util.Collection<YearMonth> months, YearMonth firstMonth) {
        List<UUID> out = new ArrayList<>();
        DataGraph g = eraser.graph();
        StringBuilder notReferenced = new StringBuilder();
        for (DataGraph.Ref ref : g.refs()) {
            if ("contributions".equals(ref.parent()) || (ref.parent() == null && !ref.nullable())) {
                notReferenced.append(" and not exists (select 1 from ").append(ref.child()).append(" x where x.")
                        .append(ref.column()).append(" = c.id)");
            }
        }
        for (YearMonth ym : months) {
            if (firstMonth != null && ym.isBefore(firstMonth)) {
                continue;
            }
            out.addAll(jdbc.queryForList("select c.id from contributions c where c.cooperative_id = ? and c.\"year\" = ? and c.\"month\" = ?"
                            + " and c.paid_amount = 0 and coalesce(c.submitted_amount, 0) = 0 and c.status = 'PENDING'"
                            + notReferenced, UUID.class, cooperativeId, ym.getYear(), ym.getMonthValue()));
        }
        return out;
    }

    private String username(String userId) {
        try {
            return jdbc.queryForList("select username from users where id = ?", String.class, UUID.fromString(userId))
                    .stream().findFirst().orElse(userId);
        } catch (IllegalArgumentException e) {
            return userId;
        }
    }
}
