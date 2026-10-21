package rw.terimbere.csams.modules.report.export;

/**
 * User-facing scheme wording on exported documents. Internal APIs/DB still use cooperative.
 */
public final class ReportLabels {

    public static final String SCHEME = "Saving Scheme";

    private ReportLabels() {}

    /** Maps stored audit entity types onto document wording. */
    public static String entityType(String stored) {
        if ("Cooperative".equals(stored)) {
            return SCHEME;
        }
        return stored == null ? "" : stored;
    }
}
