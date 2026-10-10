package org.example.subscriptions.service;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;

@Component
@ScheduledJob(name = "subscription-statuses", cron = "* * * * *")
public class SubscriptionStatusTask implements BackgroundTask {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionStatusTask.class);
    private final SubscriptionReader subscriptions;
    private final DocumentOperations operations;
    private final Clock clock;

    public SubscriptionStatusTask(SubscriptionReader subscriptions, DocumentOperations operations, Clock clock) {
        this.subscriptions = subscriptions;
        this.operations = operations;
        this.clock = clock;
    }

    @Override
    public void execute() {
        LocalDate today = LocalDate.now(clock);
        int changed = 0;
        for (var candidate : subscriptions.findByPostedTrueAndDeletionMarkFalse())
            if (operations.refreshStatus(candidate.getId(), today)) changed++;
        log.info("Subscription statuses completed: businessDate={}, changed={}", today, changed);
    }
}
