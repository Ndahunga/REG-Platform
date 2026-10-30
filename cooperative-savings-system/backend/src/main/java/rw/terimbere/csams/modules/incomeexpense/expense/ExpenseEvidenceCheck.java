package rw.terimbere.csams.modules.incomeexpense.expense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptReader;

/**
 * Compares the amount recorded for an expense with what its supporting document (receipt, invoice, bill) shows. The
 * document is read by the same reader as payment receipts (text layer of a PDF, or OCR of a photo). Pure: no database.
 */
public final class ExpenseEvidenceCheck {

    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String STATEMENT_LINE = "STATEMENT_LINE";

    /** The verdict, the amount the document seems to show (when found) and a short note. */
    public record Result(String check, BigDecimal amountRead, String note) {}

    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:[,\\s]\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?(?![\\d])");

    private ExpenseEvidenceCheck() {}

    public static Result check(BigDecimal recorded, ReceiptReader.Outcome outcome) {
        if (outcome == null || !ReceiptReader.COMPLETED.equals(outcome.status())) {
            String why = outcome == null ? "NOT_READ" : outcome.failureCode() == null ? outcome.status() : outcome.failureCode();
            return new Result(UNREADABLE, null, "The document could not be read automatically (" + why + "); check it by eye");
        }
        List<BigDecimal> seen = new ArrayList<>();
        if (outcome.parsed() != null) {
            if (outcome.parsed().amount() != null && outcome.parsed().amount().value() != null) {
                seen.add(outcome.parsed().amount().value());
            }
            if (outcome.parsed().amountCandidates() != null) {
                seen.addAll(outcome.parsed().amountCandidates());
            }
        }
        if (outcome.ocr() != null && outcome.ocr().text() != null) {
            seen.addAll(numbers(outcome.ocr().text()));
        }
        for (BigDecimal value : seen) {
            if (value != null && value.compareTo(recorded) == 0) {
                return new Result(MATCH, recorded, null);
            }
        }
        BigDecimal best = outcome.parsed() != null && outcome.parsed().amount() != null ? outcome.parsed().amount().value() : null;
        return new Result(MISMATCH, best, null);
    }

    /** Every amount-looking number in a text: "45,000", "45 000", "45000.00". */
    static List<BigDecimal> numbers(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            try {
                String whole = m.group(1).replaceAll("[,\\s]", "");
                out.add(new BigDecimal(m.group(2) == null ? whole : whole + "." + m.group(2)));
            } catch (NumberFormatException ignored) {
                // not a number after all
            }
        }
        return out;
    }
}
