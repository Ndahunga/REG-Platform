package rw.terimbere.csams.modules.report.whatsapp;

import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Converts Rwandan cooperative phone numbers into WhatsApp Cloud API recipients
 * ({@code 2507XXXXXXXX}, no plus sign).
 */
public final class WhatsAppPhone {

    private WhatsAppPhone() {}

    public static String toRecipient(String raw) {
        if (!CooperativeFieldRules.isValidRwandanPhone(raw)) {
            return null;
        }
        String local = CooperativeFieldRules.normalizePhone(raw);
        if (local == null || local.length() < 2) {
            return null;
        }
        return "250" + local.substring(1);
    }
}
