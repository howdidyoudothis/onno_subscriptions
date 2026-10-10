package org.example.subscriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.Tariff;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:statuses04;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(StatusFixture.Support.class)
class StatusIntegrationTest extends StatusFixture {
    @Test
    void datesAndRepeatKeepExactAggregateAndLedger() {
        clock.day("2024-02-27");
        UUID documentId = createSubscription("2024-02-28", 2);
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        var before = financialState(documentId);
        var ledger = ledgerSnapshot();

        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.WAITING);
        long waitingVersion = readSubscription(documentId).getVersion();
        task.execute();

        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.WAITING);
        assertThat(readSubscription(documentId).getVersion()).isEqualTo(waitingVersion);
        for (var point : List.of(Map.entry("2024-02-28", SubscriptionStatus.ACTIVE), Map.entry("2024-02-29",
                SubscriptionStatus.ACTIVE),
                Map.entry("2024-03-01", SubscriptionStatus.EXPIRED), Map.entry("2024-03-02", SubscriptionStatus.EXPIRED))) {
            clock.day(point.getKey());
            task.execute();
            assertThat(readSubscription(documentId).getStatus()).isEqualTo(point.getValue());
            assertThat(readSubscription(documentId).isPosted()).isTrue();
            assertThat(financialState(documentId)).isEqualTo(before);
            assertThat(ledgerSnapshot()).isEqualTo(ledger);
            long version = readSubscription(documentId).getVersion();
            task.execute();
            assertThat(readSubscription(documentId).getVersion()).isEqualTo(version);
        }
    }

    @Test
    void skipDraftCancelledAndDeletedAndAllowDeletedHistoricalTariff() {
        UUID draft = createSubscription("2026-01-02", 1), cancelled = createSubscription("2026-01-02", 1),
                deleted = createSubscription("2026-01-02", 1), history = createSubscription("2026-01-02", 1);
        for (UUID documentId : List.of(cancelled, deleted, history)) commands.post(subscriptionDescriptor,
                documentId, ADMIN);
        operations.cancel(cancelled, ADMIN);
        // Test-only historical state, impossible through the protected external delete path.
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + subscriptionDescriptor.tableName() + " SET _deletion_mark=TRUE WHERE _id=:id")
                .bind("id", deleted).execute());
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("price", "500", "periodDays",
                365, "available", false), ADMIN);
        catalogs.delete(metadata.getCatalogDescriptor(Tariff.class), tariffId, ADMIN);
        var before = financialState(history);
        var ledger = ledgerSnapshot();
        clock.day("2026-01-02");
        task.execute();

        assertThat(readSubscription(draft).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        assertThat(readSubscription(cancelled).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(readSubscription(deleted).getStatus()).isEqualTo(SubscriptionStatus.WAITING);
        assertThat(readSubscription(history).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(financialState(history)).isEqualTo(before);
        assertThat(ledgerSnapshot()).isEqualTo(ledger);
    }

    @Test
    void jobAgainstCancelAndUnpostSharesDocumentLock() throws Exception {
        for (boolean cancel : List.of(true, false)) {
            clock.day("2026-01-01");
            UUID documentId = createSubscription("2026-01-02", 1);
            commands.post(subscriptionDescriptor, documentId, ADMIN);
            clock.day("2026-01-02");
            CountDownLatch ready = new CountDownLatch(2);
            var pool = Executors.newFixedThreadPool(2);
            var futures = new ArrayList<Future<?>>();
            try {
                new TransactionTemplate(manager).execute(ignored -> {
                    reader.findLockedById(documentId).orElseThrow();
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        task.execute();
                    }));
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        if (cancel) operations.cancel(documentId, ADMIN);
                        else commands.unpost(subscriptionDescriptor, documentId, ADMIN);
                    }));
                    try {
                        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                        // Both operations must wait while this transaction owns the common row lock.
                        for (var pending : futures) assertThatThrownBy(() -> pending.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return null;
                });
                for (var pending : futures) pending.get(20, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(readSubscription(documentId).getStatus()).isEqualTo(cancel ? SubscriptionStatus.CANCELLED : SubscriptionStatus.DRAFT);
            assertThat(readSubscription(documentId).isPosted()).isFalse();
            assertThat(activeMovementCount(documentId)).isZero();
        }
    }

    @Test
    void failedStatusWritePropagatesAndRollsBack() {
        UUID documentId = createSubscription("2026-01-02", 1);
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        var before = financialState(documentId);
        var ledger = ledgerSnapshot();
        clock.day("2026-01-02");
        jdbi.setSqlLogger(new SqlLogger() {
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().startsWith("UPDATE " + subscriptionDescriptor.tableName() + " SET status")) throw new IllegalStateException("test status failure");
            }
        });
        try {
            assertThatThrownBy(task::execute).isInstanceOf(RuntimeException.class);
        } finally {
            jdbi.setSqlLogger(SqlLogger.NOP_SQL_LOGGER);
        }
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.WAITING);
        assertThat(financialState(documentId)).isEqualTo(before);
        assertThat(ledgerSnapshot()).isEqualTo(ledger);
    }
}
