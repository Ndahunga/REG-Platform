package rw.terimbere.csams.modules.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Scores how well an app record (a member payment or a ledger entry) explains a statement line. Pure: no database.
 *
 * <ul>
 *   <li><b>Matched automatically</b> only when the record's transaction reference appears in the line and the amount is
 *       the same, and no other record does as well.
 *   <li><b>Suggested</b> when the amount is the same and the line carries the member's phone number, or their name with
 *       dates within a week, with one clear best candidate: an officer confirms with one click.
 *   <li>Anything else is left for an officer.
 * </ul>
 */
public final class StatementMatcher {

    public static final int AUTO = 100;
    public static final int SUGGEST = 50;

    /** An app record that could explain a line. */
    public record Candidate(String kind, UUID id, UUID memberId, String memberName, String memberPhone, BigDecimal amount,
            LocalDate date, String reference, String label) {}

    public record Scored(Candidate candidate, int score, List<String> reasons, boolean referenceHit) {}

    public record Verdict(String status, Scored best) {}

    private StatementMatcher() {}

    public static Verdict decide(String narration, String lineReference, BigDecimal amount, LocalDate date, List<Candidate> candidates) {
        String text = alnum(narration) + " " + alnum(lineReference);
        String digits = text.replaceAll("\\D", " ");
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.amount() == null || c.amount().compareTo(amount) != 0) {
                continue;
            }
            int score = 0;
            List<String> reasons = new ArrayList<>();
            reasons.add("SAME_AMOUNT");
            boolean refHit = false;
            String ref = alnum(c.reference()).replace(" ", "");
            if (ref.length() >= 6 && text.replace(" ", "").contains(ref)) {
                score += AUTO;
                refHit = true;
                reasons.add("SAME_REFERENCE");
            }
            String phone = c.memberPhone() == null ? "" : c.memberPhone().replaceAll("\\D", "");
            if (phone.length() >= 9 && digits.replace(" ", "").contains(phone.substring(phone.length() - 9))) {
                score += 50;
                reasons.add("MEMBER_PHONE");
            }
            int names = 0;
            int longNames = 0;
            if (c.memberName() != null) {
                for (String token : alnum(c.memberName()).split(" ")) {
                    if (token.length() >= 3 && (" " + text + " ").contains(" " + token + " ")) {
                        names++;
                        if (token.length() >= 5) {
                            longNames++;
                        }
                    }
                }
            }
            if (names >= 2) {
                score += 45;
                reasons.add("MEMBER_NAME");
            } else if (longNames == 1) {
                score += 20;
                reasons.add("PART_OF_MEMBER_NAME");
            }
            if (c.date() != null && date != null) {
                long days = Math.abs(ChronoUnit.DAYS.between(c.date(), date));
                if (days > 31) {
                    continue;
                }
                if (days <= 2) {
                    score += 20;
                    reasons.add("SAME_DAYS");
                } else if (days <= 7) {
                    score += 10;
                    reasons.add("WITHIN_A_WEEK");
                } else if (days > 15) {
                    score -= 20;
                }
            }
            scored.add(new Scored(c, score, reasons, refHit));
        }
        if (scored.isEmpty()) {
            return new Verdict("UNMATCHED", null);
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        Scored best = scored.get(0);
        int second = scored.size() > 1 ? scored.get(1).score() : Integer.MIN_VALUE;
        boolean clear = best.score() - second >= 10;
        if (best.referenceHit() && clear) {
            return new Verdict("MATCHED", best);
        }
        if (best.score() >= SUGGEST && clear) {
            return new Verdict("SUGGESTED", best);
        }
        return new Verdict("UNMATCHED", null);
    }

    /** A line the bank itself charged (fees, commissions, taxes): suggested as a bank charge. */
    public static boolean looksLikeBankCharge(String narration) {
        String n = narration == null ? "" : narration.toUpperCase(Locale.ROOT);
        return n.contains("CHARGE") || n.contains("COMMISSION") || n.contains(" FEE") || n.startsWith("FEE") || n.contains("EXCISE")
                || n.contains("LEDGER FEE") || n.contains("SMS ALERT") || n.contains("TAX ON");
    }

    static String alnum(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}
