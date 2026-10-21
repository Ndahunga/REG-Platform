package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.shared.exceptions.BusinessException;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            PaymentStatus.SUBMITTED, Set.of(PaymentStatus.UNDER_REVIEW, PaymentStatus.REJECTED),
            PaymentStatus.UNDER_REVIEW, Set.of(PaymentStatus.VERIFIED, PaymentStatus.REJECTED),
            PaymentStatus.VERIFIED, Set.of(PaymentStatus.POSTED),
            PaymentStatus.POSTED, Set.of(PaymentStatus.REVERSED),
            PaymentStatus.REJECTED, Set.of(),
            PaymentStatus.REVERSED, Set.of());

    @Test
    void transitionMatrixIsExactlyTheDefinedOne() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void nothingMayBeVerifiedOrPostedWithoutReview() {
        assertThat(PaymentStatus.SUBMITTED.canTransitionTo(PaymentStatus.VERIFIED)).isFalse();
        assertThat(PaymentStatus.SUBMITTED.canTransitionTo(PaymentStatus.POSTED)).isFalse();
        assertThat(PaymentStatus.UNDER_REVIEW.canTransitionTo(PaymentStatus.POSTED)).isFalse();
    }

    @Test
    void invalidTransitionIsRejectedWithAnErrorCode() {
        assertThatThrownBy(() -> PaymentStatus.SUBMITTED.requireTransitionTo(PaymentStatus.POSTED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo("INVALID_PAYMENT_TRANSITION");
    }

    @Test
    void postingTwiceHasItsOwnErrorCode() {
        assertThatThrownBy(() -> PaymentStatus.POSTED.requireTransitionTo(PaymentStatus.POSTED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo("PAYMENT_ALREADY_POSTED");
    }

    @Test
    void rejectedAndReversedAreTerminal() {
        assertThat(PaymentStatus.REJECTED.isTerminal()).isTrue();
        assertThat(PaymentStatus.REVERSED.isTerminal()).isTrue();
        assertThat(PaymentStatus.POSTED.isTerminal()).isFalse();
    }

    @Test
    void onlyRejectedReleasesItsReference() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.holdsReference()).isEqualTo(status != PaymentStatus.REJECTED);
        }
    }

    @Test
    void onlyAuthorizedStatesRequireFullAllocation() {
        assertThat(PaymentStatus.SUBMITTED.requiresFullAllocation()).isFalse();
        assertThat(PaymentStatus.UNDER_REVIEW.requiresFullAllocation()).isFalse();
        assertThat(PaymentStatus.VERIFIED.requiresFullAllocation()).isTrue();
        assertThat(PaymentStatus.POSTED.requiresFullAllocation()).isTrue();
        assertThat(PaymentStatus.REVERSED.requiresFullAllocation()).isTrue();
    }
}
