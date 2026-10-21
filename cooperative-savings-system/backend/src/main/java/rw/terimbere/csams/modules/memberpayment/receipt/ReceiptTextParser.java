package rw.terimbere.csams.modules.memberpayment.receipt;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

/**
 * Pulls payment facts out of receipt text. Pure and deterministic: no I/O, no state.
 *
 * <p>Every extracted field carries a confidence from 0 to 1: how strong the pattern that found it is (a labelled
 * "Transaction ID: ..." is stronger than a bare token), multiplied by how well the OCR engine read the text. A field
 * that cannot be found is simply absent: nothing is guessed. Whatever is found is a CLAIM made by an image; it is never
 * evidence that money was received.
 */
public final class ReceiptTextParser {

    static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    public record Field<T>(T value, double confidence) {}

    public record Parsed(
            Field<String> reference,
            Field<BigDecimal> amount,
            Field<String> currency,
            Field<LocalDateTime> paidAt,
            Field<String> sender,
            Field<String> recipient,
            Field<PaymentMethod> provider,
            Field<ReceiptStatus> status,
            boolean amountAmbiguous,
            List<BigDecimal> amountCandidates) {}

    private static final Pattern LABELLED_REFERENCE = Pattern.compile(
            "(?i)(?:financial\\s+transaction\\s+id|transaction\\s*(?:id|no\\.?|number|ref(?:erence)?)|txn\\s*(?:id|no\\.?)"
                    + "|trx\\s*(?:id|no\\.?)|reference(?:\\s*(?:no\\.?|number|id))?|ref\\s*(?:no\\.?|id)?)"
                    + "\\s*[:#\\-]?\\s*([A-Za-z0-9][A-Za-z0-9\\-]{5,31})");
    private static final Pattern BARE_REFERENCE =
            Pattern.compile("\\b(?=[A-Z0-9\\-]*\\d)(?=[A-Z0-9\\-]*[A-Z])[A-Z0-9\\-]{8,24}\\b");
    private static final String NUMBER = "([0-9]{1,3}(?:[,\\s][0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)";
    private static final Pattern CURRENCY_AMOUNT = Pattern.compile("\\b(RWF|FRW|USD|EUR)\\s*" + NUMBER);
    private static final Pattern ISO_DATE_TIME =
            Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern DMY_SLASH_TIME =
            Pattern.compile("\\b(\\d{2})/(\\d{2})/(\\d{4})[ ,]+(\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern DMY_DASH_TIME =
            Pattern.compile("\\b(\\d{2})-(\\d{2})-(\\d{4})[ ,]+(\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DMY_SLASH = Pattern.compile("\\b(\\d{2})/(\\d{2})/(\\d{4})\\b");
    private static final String PHONE = "((?:\\+?250|0)7[2389]\\d{7})";
    private static final Pattern SENDER_PHONE =
            Pattern.compile("(?i)(?:from|sender|sent\\s*by|payer)[^0-9+\\n]{0,24}" + PHONE);
    private static final Pattern RECIPIENT_PHONE =
            Pattern.compile("(?i)(?:\\bto\\b|recipient|receiver|beneficiary|paid\\s*to|merchant)[^0-9+\\n]{0,30}" + PHONE);
    private static final String[] BANK_NAMES = {
        "bank of kigali", "equity", "i&m", "ecobank", "access bank", "kcb", "gt bank", "gtbank", "cogebanque", "zigama",
        "bpr", "banque populaire", "bank transfer", "swift", "iban"
    };

    private ReceiptTextParser() {}

    /** @param ocrConfidence the engine's mean confidence from 0 to 1 (use 1 for text that did not come from OCR) */
    public static Parsed parse(String text, double ocrConfidence) {
        return parse(text, ocrConfidence, ReceiptStatusClassifier.withDefaults());
    }

    /** Parses with the deployment's receipt vocabulary. */
    public static Parsed parse(String text, double ocrConfidence, ReceiptStatusClassifier classifier) {
        String body = text == null ? "" : text;
        double read = Math.max(0, Math.min(1, ocrConfidence));
        ReceiptAmountExtractor.Reading reading = ReceiptAmountExtractor.extract(body);
        Field<BigDecimal> amount =
                reading.amount() == null ? null : new Field<>(reading.amount(), scaled(reading.strength(), read));
        Field<String> currency = reading.currency() == null ? null : new Field<>(reading.currency(), scaled(0.8, read));
        if (currency == null && (amount != null || reading.ambiguous())) {
            Matcher any = CURRENCY_AMOUNT.matcher(body);
            if (any.find()) {
                currency = new Field<>(currencyOf(any.group(1)), scaled(0.6, read));
            }
        }
        return new Parsed(
                reference(body, read),
                amount,
                currency,
                paidAt(body, read),
                phone(SENDER_PHONE, body, read),
                phone(RECIPIENT_PHONE, body, read),
                provider(body, read),
                status(body, read, classifier),
                reading.ambiguous(),
                reading.candidates());
    }

    private static Field<String> reference(String body, double read) {
        Matcher labelled = LABELLED_REFERENCE.matcher(body);
        while (labelled.find()) {
            String token = labelled.group(1);
            if (token.chars().anyMatch(Character::isDigit)) {
                return new Field<>(token.toUpperCase(Locale.ROOT), scaled(0.9, read));
            }
        }
        Matcher bare = BARE_REFERENCE.matcher(body.toUpperCase(Locale.ROOT));
        if (bare.find()) {
            return new Field<>(bare.group(), scaled(0.4, read));
        }
        return null;
    }

    private static Field<LocalDateTime> paidAt(String body, double read) {
        LocalDateTime iso = dateTime(ISO_DATE_TIME.matcher(body), 1, 2, 3, 4, 5, 6);
        if (iso != null) {
            return new Field<>(iso, scaled(0.85, read));
        }
        LocalDateTime slash = dateTime(DMY_SLASH_TIME.matcher(body), 3, 2, 1, 4, 5, 6);
        if (slash != null) {
            return new Field<>(slash, scaled(0.85, read));
        }
        LocalDateTime dash = dateTime(DMY_DASH_TIME.matcher(body), 3, 2, 1, 4, 5, 6);
        if (dash != null) {
            return new Field<>(dash, scaled(0.85, read));
        }
        Matcher dateOnly = ISO_DATE.matcher(body);
        if (dateOnly.find()) {
            LocalDate date = date(dateOnly.group(1), dateOnly.group(2), dateOnly.group(3));
            if (date != null) {
                return new Field<>(date.atStartOfDay(), scaled(0.55, read));
            }
        }
        Matcher dmy = DMY_SLASH.matcher(body);
        if (dmy.find()) {
            LocalDate date = date(dmy.group(3), dmy.group(2), dmy.group(1));
            if (date != null) {
                return new Field<>(date.atStartOfDay(), scaled(0.55, read));
            }
        }
        return null;
    }

    private static LocalDateTime dateTime(Matcher m, int y, int mo, int d, int h, int mi, int s) {
        while (m.find()) {
            LocalDate date = date(m.group(y), m.group(mo), m.group(d));
            if (date == null) {
                continue;
            }
            try {
                int second = m.group(s) == null ? 0 : Integer.parseInt(m.group(s));
                return date.atTime(Integer.parseInt(m.group(h)), Integer.parseInt(m.group(mi)), second);
            } catch (DateTimeException ignored) {
                // an impossible time: keep looking
            }
        }
        return null;
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static Field<String> phone(Pattern pattern, String body, double read) {
        Matcher m = pattern.matcher(body);
        if (m.find()) {
            String digits = m.group(1).replace("+", "");
            String national = digits.startsWith("250") ? digits.substring(3) : digits.substring(1);
            return new Field<>("+250" + national, scaled(0.8, read));
        }
        return null;
    }

    private static Field<PaymentMethod> provider(String body, double read) {
        String lower = body.toLowerCase(Locale.ROOT);
        boolean mtn = lower.contains("mtn") || lower.contains("momo");
        boolean airtel = lower.contains("airtel");
        boolean bank = false;
        for (String name : BANK_NAMES) {
            bank |= lower.contains(name);
        }
        int hits = (mtn ? 1 : 0) + (airtel ? 1 : 0) + (bank ? 1 : 0);
        if (hits != 1) {
            return null; // none, or contradictory: never guess
        }
        if (mtn) {
            return new Field<>(PaymentMethod.MTN_MOMO, scaled(0.85, read));
        }
        if (airtel) {
            return new Field<>(PaymentMethod.AIRTEL_MONEY, scaled(0.85, read));
        }
        return new Field<>(PaymentMethod.BANK_TRANSFER, scaled(0.7, read));
    }

    private static Field<ReceiptStatus> status(String body, double read, ReceiptStatusClassifier classifier) {
        ReceiptStatusClassifier.Classification result = classifier.classify(body);
        return new Field<>(result.status(), scaled(result.strength(), read));
    }

    private static String currencyOf(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        return upper.equals("FRW") ? "RWF" : upper;
    }

    private static double scaled(double pattern, double read) {
        return Math.round(pattern * read * 10_000d) / 10_000d;
    }
}
