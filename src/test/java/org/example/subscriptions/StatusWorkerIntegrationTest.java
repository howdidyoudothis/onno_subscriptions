package org.example.subscriptions;

import java.time.Instant;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.service.SubscriptionStatusTask;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.navigation.AmountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:worker04;DB_CLOSE_DELAY=-1",
    "org.jobrunr.background-job-server.enabled=true", "org.jobrunr.background-job-server.worker-count=1",
            "org.jobrunr.background-job-server.poll-interval-in-seconds=5"
})
@ActiveProfiles("test")
@Import(StatusFixture.Support.class)
@DirtiesContext
class StatusWorkerIntegrationTest extends StatusFixture {
    @Autowired
    StorageProvider storage;

    @Test
    void actualRecurringWorkerChangesPersistedStatus() throws Exception {
        var recurring = storage.getRecurringJobs().stream().filter(job -> job.getId().equals("subscription-statuses")).findFirst()
                .orElseThrow();

        assertThat(recurring.getScheduleExpression()).isEqualTo("* * * * *");
        assertThat(task.getClass()).isEqualTo(SubscriptionStatusTask.class);
        var documentId = createSubscription("2026-01-02", 1);
        commands.post(subscriptionDescriptor, documentId, ADMIN);

        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.WAITING);
        var ledger = ledgerSnapshot();
        var before = financialState(documentId);
        clock.day("2026-01-02");
        System.out.println("WORKER04 before=" + documentId + " WAITING nextRun=" + recurring.getNextRun());
        Instant deadline = Instant.now().plusSeconds(100);
        boolean succeeded = false;
        while (Instant.now().isBefore(deadline)) {
            succeeded = storage.getJobList(StateName.SUCCEEDED, new AmountRequest("updatedAt:DESC", 100)).stream().anyMatch(job -> job.getRecurringJobId()
                    .orElse("").equals("subscription-statuses"));
            if (succeeded && readSubscription(documentId).getStatus() == SubscriptionStatus.ACTIVE) break;
            Thread.sleep(250);
        }
        assertThat(succeeded).as("registered recurring job executed by real worker within timeout").isTrue();
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(financialState(documentId)).isEqualTo(before);
        assertThat(ledgerSnapshot()).isEqualTo(ledger);
        System.out.println("WORKER04 after=" + documentId + " ACTIVE recurring SUCCEEDED, aggregate and ledger unchanged");
    }
}
