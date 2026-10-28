package rw.terimbere.csams.modules.memberpayment.entity;

import java.util.Set;

/**
 * How the receipt of funds was confirmed. Receipt images and OCR output are deliberately not a method: they are
 * evidence to look at, never a confirmation. Provider and statement integrations are later work; today these are
 * recorded by an authorized officer together with the basis they relied on.
 */
public enum VerificationMethod {
    /** An authorized officer confirmed the cash was received. */
    CASH_RECEIPT_CONFIRMED,
    /** The mobile-money transaction was matched against the provider's records. */
    MOBILE_MONEY_RECONCILED,
    /** The bank transaction was matched against the bank statement. */
    BANK_RECONCILED,
    /** A trusted payment provider confirmed the transaction. */
    PROVIDER_CONFIRMED;

    /** Only a cash confirmation may omit an external reference. */
    public boolean requiresReference() {
        return this != CASH_RECEIPT_CONFIRMED;
    }

    /** A method must fit how the member paid: cash is confirmed as cash, electronic payments as electronic ones. */
    public boolean fits(PaymentMethod paymentMethod) {
        Set<VerificationMethod> allowed =
                switch (paymentMethod) {
                    case CASH -> Set.of(CASH_RECEIPT_CONFIRMED);
                    // mobile money paid into the scheme's bank account shows on the bank statement
                    case MTN_MOMO, AIRTEL_MONEY -> Set.of(MOBILE_MONEY_RECONCILED, BANK_RECONCILED, PROVIDER_CONFIRMED);
                    case BANK_TRANSFER -> Set.of(BANK_RECONCILED, PROVIDER_CONFIRMED);
                    case OTHER -> Set.of(
                            MOBILE_MONEY_RECONCILED, BANK_RECONCILED, PROVIDER_CONFIRMED, CASH_RECEIPT_CONFIRMED);
                };
        return allowed.contains(this);
    }
}
