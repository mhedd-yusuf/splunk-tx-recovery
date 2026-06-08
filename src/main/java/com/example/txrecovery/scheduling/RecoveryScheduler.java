package com.example.txrecovery.scheduling;

import com.example.txrecovery.domain.service.RecoveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point. The cron expression and ShedLock TTL are both
 * configurable. Catches and logs all exceptions so a single failure does not
 * kill the scheduler thread.
 */
@Component
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RecoveryService recoveryService;

    public RecoveryScheduler(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /** Runs one recovery cycle under a distributed ShedLock. */
    @Scheduled(cron = "${recovery.schedule-cron}")
    @SchedulerLock(
            name = "RecoveryScheduler_run",
            lockAtMostFor = "${recovery.lock-at-most-for}",
            lockAtLeastFor = "PT0S")
    public void run() {
        try {
            recoveryService.runOnce();
        } catch (RuntimeException e) {
            // Already logged at warn inside RecoveryService; we swallow here so
            // the @Scheduled thread keeps firing.
            log.debug("recovery-run threw; suppressed at scheduler boundary", e);
        }
    }
}
