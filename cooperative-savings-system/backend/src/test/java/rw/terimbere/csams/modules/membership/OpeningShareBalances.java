package rw.terimbere.csams.modules.membership;

import java.util.UUID;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;

/**
 * Test-only helper: apply an opening/historical share count the same way historical import does.
 * Ordinary register/update APIs no longer accept purchased-share assignments.
 */
public final class OpeningShareBalances {

    private OpeningShareBalances() {}

    public static void set(
            CooperativeMembershipRepository membershipRepository,
            UUID cooperativeId,
            UUID memberUserId,
            int shareCount) {
        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow();
        membership.setShareCount(shareCount);
        membershipRepository.save(membership);
    }
}
