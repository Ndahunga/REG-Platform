package rw.terimbere.csams.modules.memberpayment.dto;

import java.time.Instant;
import java.util.UUID;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;

/** One status change. The acting user's id is shown to staff only; members see the acting role. */
public record MemberPaymentEventResponse(
        UUID id,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String actorRole,
        UUID actorUserId,
        String comment,
        Instant createdAt) {

    public static MemberPaymentEventResponse from(MemberPaymentEvent e, boolean staffView) {
        return new MemberPaymentEventResponse(
                e.getId(),
                e.getFromStatus(),
                e.getToStatus(),
                e.getActorRole(),
                staffView ? e.getActorUserId() : null,
                e.getComment(),
                e.getCreatedAt());
    }
}
