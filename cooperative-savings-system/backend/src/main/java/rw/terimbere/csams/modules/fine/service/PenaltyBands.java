package rw.terimbere.csams.modules.fine.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Penalty by bands of days late: {@code "1-5:2000;6-15:5000;16-30:10000"}. A day count beyond the last band takes the
 * last band's amount; a day count before the first band is no penalty.
 */
public final class PenaltyBands {

    public record Band(int fromDay, int toDay, BigDecimal amount) {}

    private PenaltyBands() {}

    public static List<Band> parse(String text) {
        List<Band> bands = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return bands;
        }
        for (String part : text.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                String[] kv = p.split(":");
                String[] range = kv[0].trim().split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                BigDecimal amount = new BigDecimal(kv[1].trim().replace(",", ""));
                if (from < 1 || to < from || amount.signum() < 0) {
                    throw new IllegalArgumentException();
                }
                bands.add(new Band(from, to, amount));
            } catch (RuntimeException e) {
                throw new ValidationException("Penalty band \"" + p + "\" must look like 1-5:2000");
            }
        }
        bands.sort(Comparator.comparingInt(Band::fromDay));
        for (int i = 1; i < bands.size(); i++) {
            if (bands.get(i).fromDay() <= bands.get(i - 1).toDay()) {
                throw new ValidationException("Penalty bands overlap");
            }
        }
        return bands;
    }

    public static BigDecimal amountFor(String text, int daysLate) {
        List<Band> bands = parse(text);
        if (bands.isEmpty() || daysLate < bands.get(0).fromDay()) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (Band b : bands) {
            if (daysLate >= b.fromDay()) {
                amount = b.amount();
            }
        }
        return amount;
    }
}
