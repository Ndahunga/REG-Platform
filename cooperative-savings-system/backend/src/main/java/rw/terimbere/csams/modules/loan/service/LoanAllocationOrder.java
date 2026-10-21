package rw.terimbere.csams.modules.loan.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.shared.exceptions.ValidationException;

public final class LoanAllocationOrder {

    public static final String DEFAULT = "PENALTY,INTEREST,PRINCIPAL";

    private LoanAllocationOrder() {}

    public static List<LoanRepaymentComponent> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return parse(DEFAULT);
        }
        String[] parts = raw.split(",");
        List<LoanRepaymentComponent> order = new ArrayList<>();
        Set<LoanRepaymentComponent> seen = EnumSet.noneOf(LoanRepaymentComponent.class);
        for (String part : parts) {
            String token = part.trim().toUpperCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            LoanRepaymentComponent component;
            try {
                component = LoanRepaymentComponent.valueOf(token);
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("Unknown repayment allocation component: " + token);
            }
            if (!seen.add(component)) {
                throw new ValidationException("Repayment allocation order cannot repeat " + component);
            }
            order.add(component);
        }
        if (order.size() != LoanRepaymentComponent.values().length
                || !seen.containsAll(EnumSet.allOf(LoanRepaymentComponent.class))) {
            throw new ValidationException(
                    "Repayment allocation order must include PENALTY, INTEREST, and PRINCIPAL exactly once");
        }
        return List.copyOf(order);
    }

    public static String serialize(List<LoanRepaymentComponent> order) {
        parse(String.join(",", order.stream().map(Enum::name).toList()));
        return String.join(",", order.stream().map(Enum::name).toList());
    }
}
