package rw.terimbere.csams.modules.memberpayment.receipt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the amount of the PAYMENT on a receipt, and says so honestly when it cannot tell.
 *
 * <p>A receipt carries several numbers: the amount sent, a fee, the sender's balance before and after, limits, phone
 * numbers and references. Each number is judged by the words just before it (and the word right after it):
 * <ul>
 *   <li>"balance", "fee", "charge", "tax", "limit" and similar words rule a number OUT of being the payment;
 *   <li>"amount", "paid", "sent", "payment", "total" and similar words make it a STRONG candidate;
 *   <li>a bare number next to a currency code is only a WEAK candidate.
 * </ul>
 * One strong candidate (or, failing that, one weak candidate) is the amount. If the strong candidates disagree the
 * reading is <i>ambiguous</i> and no amount is chosen, except for the common case where one figure is simply the other
 * plus the stated fees (an amount and a total that includes the fee), where the amount without the fee is taken.
 * Nothing here is evidence that money arrived: it is what the image says.
 */
public final class ReceiptAmountExtractor {

    /** The chosen amount, if any; whether the receipt showed competing payment amounts; and every plausible one. */
    public record Reading(BigDecimal amount, String currency, double strength, boolean ambiguous, List<BigDecimal> candidates) {
        static Reading none() {
            return new Reading(null, null, 0, false, List.of());
        }
    }

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])(\\d{1,3}(?:[,\\s]\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)");
    private static final Set<String> CURRENCIES = Set.of("rwf", "frw", "usd", "eur");
    private static final Set<String> FILLERS = Set.of("of", "is", "was", "for", "the", "a", "to", "has", "been");

    private static final List<String[]> NOT_THE_PAYMENT = phrases(
            "balance", "available balance", "new balance", "previous balance", "old balance", "balance before",
            "balance after", "current balance", "fee", "fees", "service fee", "transaction fee", "charge", "charges",
            "tax", "vat", "commission", "limit", "daily limit", "withdrawal limit", "maximum", "minimum");
    private static final Set<String> FEE_WORDS = Set.of("fee", "fees", "charge", "charges", "tax", "vat", "commission");
    private static final List<String[]> THE_PAYMENT = phrases(
            "amount", "amount paid", "paid amount", "paid", "amount sent", "sent", "transferred", "amount transferred",
            "transaction amount", "payment amount", "payment", "total amount", "total", "debited", "you paid", "you sent",
            "you have paid", "you have sent", "amafaranga");

    private ReceiptAmountExtractor() {}

    private record Candidate(BigDecimal value, String currency, boolean strong, boolean fee) {}

    public static Reading extract(String text) {
        if (text == null || text.isBlank()) {
            return Reading.none();
        }
        List<Candidate> found = new ArrayList<>();
        for (String line : text.split("\\R")) {
            collect(line, found);
        }
        List<Candidate> strong = distinct(found.stream().filter(c -> c.strong() && !c.fee()).toList());
        List<Candidate> weak = distinct(found.stream().filter(c -> !c.strong() && !c.fee()).toList());
        BigDecimal fees = found.stream().filter(Candidate::fee).map(Candidate::value).distinct()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Candidate> pool = !strong.isEmpty() ? strong : weak;
        double base = !strong.isEmpty() ? 0.9 : 0.6;
        if (pool.isEmpty()) {
            return Reading.none();
        }
        List<BigDecimal> values = pool.stream().map(Candidate::value).toList();
        if (pool.size() == 1) {
            return new Reading(pool.get(0).value(), currencyOf(pool.get(0), found), base, false, values);
        }
        if (pool.size() == 2 && fees.signum() > 0) {
            Candidate low = pool.get(0).value().compareTo(pool.get(1).value()) <= 0 ? pool.get(0) : pool.get(1);
            Candidate high = low == pool.get(0) ? pool.get(1) : pool.get(0);
            if (low.value().add(fees).compareTo(high.value()) == 0) {
                return new Reading(low.value(), currencyOf(low, found), 0.75, false, values);
            }
        }
        return new Reading(null, currencyOf(pool.get(0), found), 0, true, values);
    }

    private static void collect(String line, List<Candidate> into) {
        Matcher m = NUMBER.matcher(line);
        int previousEnd = 0;
        while (m.find()) {
            String raw = m.group(1);
            int start = m.start(1);
            int end = m.end(1);
            String before = line.substring(previousEnd, start);
            previousEnd = end;
            String after = line.substring(end);
            if (followedByLetters(after) && currencyWordAfter(after) == null) {
                continue; // part of a code such as 123ABC
            }
            String digits = raw.replaceAll("[,\\s]", "");
            boolean grouped = raw.indexOf(',') >= 0 || raw.chars().anyMatch(Character::isWhitespace);
            String integerPart = digits.contains(".") ? digits.substring(0, digits.indexOf('.')) : digits;
            if (integerPart.length() >= 10 || (integerPart.startsWith("0") && integerPart.length() >= 7)) {
                continue; // a phone number or an identifier
            }
            BigDecimal value;
            try {
                value = new BigDecimal(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            if (value.signum() <= 0) {
                continue;
            }
            String[] words = ReceiptStatusClassifier.tokens(before);
            String currency = null;
            List<String> context = new ArrayList<>();
            for (String word : words) {
                if (CURRENCIES.contains(word)) {
                    currency = word.equals("frw") ? "RWF" : word.toUpperCase(Locale.ROOT);
                    // a code ends the label words before it only if it sits right before the number
                    continue;
                }
                context.add(word);
            }
            boolean currencyBefore = words.length > 0 && CURRENCIES.contains(words[words.length - 1]);
            String currencyAfter = currencyWordAfter(after);
            if (currencyAfter != null) {
                currency = currencyAfter.equals("frw") ? "RWF" : currencyAfter.toUpperCase(Locale.ROOT);
            }
            boolean hasCurrency = currencyBefore || currencyAfter != null;
            if (!hasCurrency && integerPart.length() >= 7 && !grouped) {
                continue; // a long bare number with no currency is a reference, not an amount
            }
            while (!context.isEmpty() && FILLERS.contains(context.get(context.size() - 1))) {
                context.remove(context.size() - 1);
            }
            String[] label = context.toArray(new String[0]);
            String next = nextWord(after);
            boolean feeAfter = next != null && FEE_WORDS.contains(next);
            boolean negative = endsWithAny(label, NOT_THE_PAYMENT) || feeAfter;
            boolean fee = feeAfter || endsWithFee(label);
            boolean positive = endsWithAny(label, THE_PAYMENT);
            if (negative) {
                if (fee) {
                    into.add(new Candidate(value, currency, false, true));
                }
                continue;
            }
            if (positive) {
                into.add(new Candidate(value, currency, true, false));
            } else if (hasCurrency) {
                into.add(new Candidate(value, currency, false, false));
            }
        }
    }

    private static boolean followedByLetters(String after) {
        return !after.isEmpty() && Character.isLetter(after.charAt(0));
    }

    private static String currencyWordAfter(String after) {
        String[] words = ReceiptStatusClassifier.tokens(after.length() > 12 ? after.substring(0, 12) : after);
        return words.length > 0 && CURRENCIES.contains(words[0]) ? words[0] : null;
    }

    private static String nextWord(String after) {
        String[] words = ReceiptStatusClassifier.tokens(after.length() > 24 ? after.substring(0, 24) : after);
        int i = 0;
        if (i < words.length && CURRENCIES.contains(words[i])) {
            i++;
        }
        return i < words.length ? words[i] : null;
    }

    private static boolean endsWithAny(String[] label, List<String[]> phrases) {
        for (String[] phrase : phrases) {
            if (label.length >= phrase.length
                    && Arrays.equals(Arrays.copyOfRange(label, label.length - phrase.length, label.length), phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithFee(String[] label) {
        return label.length > 0 && FEE_WORDS.contains(label[label.length - 1]);
    }

    private static List<Candidate> distinct(List<Candidate> all) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate c : all) {
            if (result.stream().noneMatch(r -> r.value().compareTo(c.value()) == 0)) {
                result.add(c);
            }
        }
        return result;
    }

    private static String currencyOf(Candidate chosen, List<Candidate> all) {
        if (chosen.currency() != null) {
            return chosen.currency();
        }
        return all.stream().map(Candidate::currency).filter(c -> c != null).findFirst().orElse(null);
    }

    private static List<String[]> phrases(String... entries) {
        List<String[]> result = new ArrayList<>();
        for (String entry : entries) {
            result.add(ReceiptStatusClassifier.tokens(entry));
        }
        return result;
    }
}
