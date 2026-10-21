package rw.terimbere.csams.modules.memberpayment.verification;

/** Where a piece of verification evidence comes from. Only authoritative sources can establish CONFIRMED. */
public enum VerificationSource {
    MANUAL_CASH_CONFIRMATION(true),
    BANK_STATEMENT_IMPORT(true),
    MOBILE_MONEY_STATEMENT_IMPORT(true),
    PROVIDER_CALLBACK(true),
    /** What OCR read from a receipt image. Advisory: it can raise doubt, never confirm. */
    RECEIPT_OCR(false),
    /** A reference that merely looks the same. Never confirms by itself. */
    REFERENCE_MATCH(false);

    private final boolean authoritative;

    VerificationSource(boolean authoritative) {
        this.authoritative = authoritative;
    }

    public boolean authoritative() {
        return authoritative;
    }

    /** A source a trusted transaction can be recorded from. */
    public boolean recordsTransactions() {
        return this == BANK_STATEMENT_IMPORT || this == MOBILE_MONEY_STATEMENT_IMPORT || this == PROVIDER_CALLBACK;
    }
}
