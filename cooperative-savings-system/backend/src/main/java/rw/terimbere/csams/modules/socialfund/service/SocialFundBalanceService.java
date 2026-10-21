package rw.terimbere.csams.modules.socialfund.service;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialDisbursementRepository;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Social fund balance is independent of available group funds.
 *
 * <p>balance = sum(APPROVED social contributions) − sum(APPROVED social disbursements)
 */
@Service
@RequiredArgsConstructor
public class SocialFundBalanceService {

    private final SocialContributionRepository contributionRepository;
    private final SocialDisbursementRepository disbursementRepository;

    @Transactional(readOnly = true)
    public BigDecimal calculateBalance(UUID cooperativeId) {
        BigDecimal contributions = sumApprovedContributions(cooperativeId);
        BigDecimal disbursements = sumApprovedDisbursements(cooperativeId);
        return MoneyUtils.subtract(contributions, disbursements);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedContributions(UUID cooperativeId) {
        BigDecimal sum = contributionRepository.sumApprovedAmount(cooperativeId);
        return MoneyUtils.scale(sum == null ? BigDecimal.ZERO : sum);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedDisbursements(UUID cooperativeId) {
        BigDecimal sum = disbursementRepository.sumApprovedAmount(cooperativeId);
        return MoneyUtils.scale(sum == null ? BigDecimal.ZERO : sum);
    }
}
