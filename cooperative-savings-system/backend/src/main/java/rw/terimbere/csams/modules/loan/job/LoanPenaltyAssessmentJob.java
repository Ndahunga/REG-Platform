package rw.terimbere.csams.modules.loan.job;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.loan.service.LoanPenaltyService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.loans.penalty-assess-enabled", havingValue = "true", matchIfMissing = true)
public class LoanPenaltyAssessmentJob {

    private static final Logger log = LoggerFactory.getLogger(LoanPenaltyAssessmentJob.class);

    private final LoanPenaltyService loanPenaltyService;

    @Scheduled(cron = "${app.loans.penalty-assess-cron:0 45 1 * * *}")
    public void assessOverdueInstallmentPenalties() {
        log.info("Assessing overdue loan-installment penalties");
        loanPenaltyService.evaluateAllActiveLoans();
    }
}
