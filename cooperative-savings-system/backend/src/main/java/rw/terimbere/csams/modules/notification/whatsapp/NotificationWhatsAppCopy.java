package rw.terimbere.csams.modules.notification.whatsapp;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concise WhatsApp copy for existing in-app notification events. Returns null for events
 * that must not be forwarded (guarantor messages, first-stage loan approval, etc.).
 */
public final class NotificationWhatsAppCopy {

    private static final String HEADER = "TERIMBERE CSAMS";
    private static final Pattern CONTRIBUTION_PERIOD = Pattern.compile("for (\\d{4})-(\\d{2})");
    private static final Pattern REJECTED_BY =
            Pattern.compile("rejected by (.+?):\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern APPROVED_BY = Pattern.compile("approved by (.+?)\\.?$");

    private NotificationWhatsAppCopy() {}

    public static String fromInApp(String title, String body) {
        if (title == null) {
            return null;
        }
        return switch (title) {
            case "Contribution submitted" -> contributionSubmitted(body);
            case "Contribution approved" -> contributionApproved(body);
            case "Contribution rejected" -> contributionRejected(body);
            case "Loan approved" -> header("Your loan request has been fully approved.");
            case "Loan rejected" -> loanRejected(body);
            case "Loan disbursed" -> header("Your loan has been disbursed.");
            default -> null;
        };
    }

    public static String contributionPending(long count) {
        if (count <= 1) {
            return header("You have 1 contribution request pending approval.");
        }
        return header("You have " + count + " contribution requests pending approval.");
    }

    public static String loanPending() {
        return header("You have a loan request pending your approval.");
    }

    private static String contributionSubmitted(String body) {
        String period = periodLabel(body);
        if (period == null) {
            return header("Your contribution was submitted for approval.");
        }
        return header("Your contribution for " + period + " was submitted for approval.");
    }

    private static String contributionApproved(String body) {
        String period = periodLabel(body);
        Matcher actor = body == null ? null : APPROVED_BY.matcher(body);
        String who = actor != null && actor.find() ? actor.group(1).trim() : null;
        StringBuilder line = new StringBuilder("Your contribution");
        if (period != null) {
            line.append(" for ").append(period);
        }
        line.append(" was approved");
        if (who != null && !who.isBlank()) {
            line.append(" by ").append(who);
        }
        line.append(".");
        return header(line.toString());
    }

    private static String contributionRejected(String body) {
        String period = periodLabel(body);
        Matcher rejected = body == null ? null : REJECTED_BY.matcher(body);
        String who = null;
        String reason = null;
        if (rejected != null && rejected.find()) {
            who = rejected.group(1).trim();
            reason = rejected.group(2).trim();
        }
        StringBuilder line = new StringBuilder("Your contribution");
        if (period != null) {
            line.append(" for ").append(period);
        }
        line.append(" was rejected");
        if (who != null && !who.isBlank()) {
            line.append(" by ").append(who);
        }
        line.append(".");
        if (reason != null && !reason.isBlank()) {
            return header(line + "\nReason: " + reason);
        }
        return header(line.toString());
    }

    private static String loanRejected(String body) {
        Matcher rejected = body == null ? null : REJECTED_BY.matcher(body);
        String who = null;
        String reason = null;
        if (rejected != null && rejected.find()) {
            who = rejected.group(1).trim();
            reason = rejected.group(2).trim();
        }
        StringBuilder line = new StringBuilder("Your loan request was rejected");
        if (who != null && !who.isBlank()) {
            line.append(" by ").append(who);
        }
        line.append(".");
        if (reason != null && !reason.isBlank()) {
            return header(line + "\nReason: " + reason);
        }
        return header(line.toString());
    }

    private static String periodLabel(String body) {
        if (body == null) {
            return null;
        }
        Matcher matcher = CONTRIBUTION_PERIOD.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) {
            return matcher.group(1) + "-" + matcher.group(2);
        }
        String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return name + " " + year;
    }

    private static String header(String body) {
        return HEADER + "\n" + body;
    }
}
