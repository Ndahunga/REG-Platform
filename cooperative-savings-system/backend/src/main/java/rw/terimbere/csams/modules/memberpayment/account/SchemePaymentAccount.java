package rw.terimbere.csams.modules.memberpayment.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * An account a Saving Scheme asks its members to pay into (a mobile-money number or a bank account). A receipt whose
 * recipient can be read is compared with the scheme's active accounts; the comparison only ever adds doubt or reassurance
 * for a person, it never verifies a payment.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheme_payment_accounts")
public class SchemePaymentAccount extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 32)
    private PaymentMethod channel;

    /** Upper-case letters and digits only, as normalised by {@link PaymentAccountService#normalize}. */
    @Column(name = "account_identifier", nullable = false, updatable = false, length = 64)
    private String accountIdentifier;

    @Column(name = "account_label", length = 100)
    private String accountLabel;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
}
