package rw.terimbere.csams.modules.filemanagement;

/**
 * Allowed upload categories for cooperative-scoped documents and images.
 */
public final class FileCategories {

    public static final String COOPERATIVE_LOGO = "COOPERATIVE_LOGO";
    public static final String PROFILE_IMAGE = "PROFILE_IMAGE";
    public static final String FINE_PAYMENT_EVIDENCE = "FINE_PAYMENT_EVIDENCE";
    public static final String CONTRIBUTION_EVIDENCE = "CONTRIBUTION_EVIDENCE";
    public static final String SHARE_PURCHASE_EVIDENCE = "SHARE_PURCHASE_EVIDENCE";
    public static final String SOCIAL_EVIDENCE = "SOCIAL_EVIDENCE";
    public static final String INVESTMENT_DOCUMENT = "INVESTMENT_DOCUMENT";
    public static final String INCOME_EXPENSE_DOCUMENT = "INCOME_EXPENSE_DOCUMENT";
    public static final String GENERAL_DOCUMENT = "GENERAL_DOCUMENT";

    private FileCategories() {}

    public static boolean isKnown(String category) {
        if (category == null) {
            return false;
        }
        return switch (category) {
            case COOPERATIVE_LOGO,
                    PROFILE_IMAGE,
                    FINE_PAYMENT_EVIDENCE,
                    CONTRIBUTION_EVIDENCE,
                    SHARE_PURCHASE_EVIDENCE,
                    SOCIAL_EVIDENCE,
                    INVESTMENT_DOCUMENT,
                    INCOME_EXPENSE_DOCUMENT,
                    GENERAL_DOCUMENT -> true;
            default -> false;
        };
    }

    public static boolean isImageOnly(String category) {
        return COOPERATIVE_LOGO.equals(category) || PROFILE_IMAGE.equals(category);
    }
}
