package rw.terimbere.csams.modules.notification.account;

import org.springframework.util.StringUtils;
import rw.terimbere.csams.security.CooperativeOfficerRoles;

/**
 * English ACCOUNT notification copy. User locale is not persisted, so this stays English.
 */
public final class AccountNotificationCopy {

    public static final String WELCOME_TITLE = "Welcome to OuWealth Community";
    public static final String ROLE_CHANGED_TITLE = "Your role has changed";
    public static final String MEMBERSHIP_ADDED_TITLE = "Saving Scheme access updated";

    private AccountNotificationCopy() {}

    public static String welcomeSignupBody() {
        return "Welcome to OuWealth Community. Your account has been created successfully. "
                + "You can now access your Saving Scheme and manage your activities securely.";
    }

    public static String welcomeNewMemberBody(String schemeName, String roleCode) {
        return "Welcome to OuWealth Community. Your account has been created successfully. "
                + "You have been added to "
                + schemeLabel(schemeName)
                + " as "
                + CooperativeOfficerRoles.displayLabel(roleCode)
                + ". Account access information is provided separately by your officer.";
    }

    public static String welcomeNewAdministratorBody(String schemeName) {
        return welcomeNewMemberBody(schemeName, CooperativeOfficerRoles.PRESIDENT);
    }

    public static String membershipAddedBody(String schemeName, String roleCode) {
        return "You have been added to "
                + schemeLabel(schemeName)
                + " as "
                + CooperativeOfficerRoles.displayLabel(roleCode)
                + ".";
    }

    public static String roleChangedBody(String schemeName, String oldRole, String newRole) {
        return "Your role in "
                + schemeLabel(schemeName)
                + " changed from "
                + CooperativeOfficerRoles.displayLabel(oldRole)
                + " to "
                + CooperativeOfficerRoles.displayLabel(newRole)
                + ".";
    }

    private static String schemeLabel(String schemeName) {
        return StringUtils.hasText(schemeName) ? schemeName.trim() : "your Saving Scheme";
    }
}
