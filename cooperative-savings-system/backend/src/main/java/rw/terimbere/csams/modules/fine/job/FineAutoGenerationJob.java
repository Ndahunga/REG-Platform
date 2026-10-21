package rw.terimbere.csams.modules.fine.job;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.fine.service.FineService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.fines.auto-generate-enabled", havingValue = "true", matchIfMissing = true)
public class FineAutoGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(FineAutoGenerationJob.class);

    private final FineService fineService;

    @Scheduled(cron = "${app.fines.auto-generate-cron:0 30 1 * * *}")
    public void applyLateContributionFines() {
        log.info("Applying automatic late-contribution fines");
        fineService.generateAutomaticForEnabledCooperatives();
    }
}
