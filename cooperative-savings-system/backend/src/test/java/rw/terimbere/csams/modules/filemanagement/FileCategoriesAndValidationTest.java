package rw.terimbere.csams.modules.filemanagement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FileCategoriesAndValidationTest {

    @Test
    void knownCategoriesIncludeEvidenceTypes() {
        assertTrue(FileCategories.isKnown(FileCategories.FINE_PAYMENT_EVIDENCE));
        assertTrue(FileCategories.isKnown(FileCategories.INVESTMENT_DOCUMENT));
        assertTrue(FileCategories.isKnown(FileCategories.INCOME_EXPENSE_DOCUMENT));
        assertTrue(FileCategories.isKnown(FileCategories.SOCIAL_EVIDENCE));
        assertTrue(FileCategories.isKnown(FileCategories.SHARE_PURCHASE_EVIDENCE));
        assertTrue(FileCategories.isKnown(FileCategories.CONTRIBUTION_EVIDENCE));
        assertFalse(FileCategories.isKnown("EXECUTABLE"));
    }

    @Test
    void imageOnlyCategoriesIdentified() {
        assertTrue(FileCategories.isImageOnly(FileCategories.COOPERATIVE_LOGO));
        assertTrue(FileCategories.isImageOnly(FileCategories.PROFILE_IMAGE));
        assertFalse(FileCategories.isImageOnly(FileCategories.FINE_PAYMENT_EVIDENCE));
    }
}
