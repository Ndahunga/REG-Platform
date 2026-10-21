package rw.terimbere.csams.modules.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "subscription_payments")
public class SubscriptionPayment extends BaseEntity {

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 32)
    private SubscriptionBillingCycle billingCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", nullable = false, length = 32)
    private SubscriptionPaymentChannel paymentChannel;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SubscriptionPaymentStatus status = SubscriptionPaymentStatus.PENDING;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "RWF";

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "external_reference", length = 128)
    private String externalReference;

    /** Normalized MTN MSISDN ({@code 2507XXXXXXXX}). PII — never log. */
    @Column(name = "payer_msisdn", length = 32)
    private String payerMsisdn;

    @Column(name = "checkout_url", length = 1024)
    private String checkoutUrl;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failed_at")
    private Instant failedAt;
}
