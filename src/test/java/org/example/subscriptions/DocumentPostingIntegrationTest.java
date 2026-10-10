package org.example.subscriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.subscriptions.model.AccountBalance;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.TariffRevenue;
import org.example.subscriptions.service.DocumentOperations;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPostingIntegrationTest extends DocumentFixture {
    List<Boolean> runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Runnable action : List.of(first, second)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try {
                    action.run();
                    return true;
                } catch (RuntimeException expectedConflict) {
                    return false;
                }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void receiptExpenseRepeatUnpostEditAndRepost() {
        UUID receipt = postPayment("1000"), documentId = createSubscription(createTariff("100", 30, true),
                2);
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        commands.post(payment, receipt, ADMIN);

        assertThat(balance(clientId)).isEqualByComparingTo("800");
        assertThat(activeMovementCount(AccountBalance.class, receipt)).isEqualTo(1);
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isEqualTo(1);
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThatThrownBy(() -> commands.unpost(payment, receipt, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThat(balance(clientId)).isEqualByComparingTo("800");
        commands.unpost(subscriptionDescriptor, documentId, ADMIN);

        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        var tariffId = readSubscription(documentId).getLines().getFirst().getTariff().id();
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(tariffLine(tariffId,
                3))), ADMIN);
        commands.post(subscriptionDescriptor, documentId, ADMIN);

        assertThat(balance(clientId)).isEqualByComparingTo("700");
    }

    @Test
    void insufficientZeroExactAndOneCentShort() {
        UUID tariffId = createTariff("100", 30, true), documentId = createSubscription(tariffId, 1);

        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, documentId, ADMIN)).isInstanceOf(RuntimeException.class);
        postPayment("99.99");

        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, documentId, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThat(balance(clientId)).isEqualByComparingTo("99.99");
        assertThat(activeMovementCount(TariffRevenue.class, documentId)).isZero();
        postPayment("0.01");
        commands.post(subscriptionDescriptor, documentId, ADMIN);

        assertThat(balance(clientId)).isEqualByComparingTo("0");
    }

    @Test
    void draftAndPostedCancellationAreFinalAndKeepInactiveHistory() {
        UUID tariffId = createTariff("100", 30, true), draft = createSubscription(tariffId, 1);
        operations.cancel(draft, ADMIN);
        operations.cancel(draft, ADMIN);

        assertThat(readSubscription(draft).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(activeMovementCount(AccountBalance.class, draft)).isZero();
        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, draft, ADMIN)).isInstanceOf(RuntimeException.class);
        postPayment("1000");
        UUID posted = createSubscription(tariffId, 1);
        commands.post(subscriptionDescriptor, posted, ADMIN);
        operations.cancel(posted, ADMIN);
        operations.cancel(posted, ADMIN);
        commands.unpost(subscriptionDescriptor, posted, ADMIN);

        assertThat(readSubscription(posted).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(readSubscription(posted).isPosted()).isFalse();
        assertThat(balance(clientId)).isEqualByComparingTo("1000");
        assertThat(activeMovementCount(TariffRevenue.class, posted)).isZero();
        long history = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + faults.revenue + " WHERE _document_ref=:id AND _active=FALSE")
                .bind("id", posted).mapTo(Long.class).one());
        assertThat(history).isEqualTo(1L);
        assertThatThrownBy(() -> commands.update(subscriptionDescriptor, posted, Map.of("status", DocumentOperations.enumId(SubscriptionStatus.DRAFT)),
                ADMIN)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void explicitPostingStatusesUseClockAndExpiredRemainsPosted() {
        postPayment("1000");
        UUID tariffId = createTariff("100", 30, true);
        for (var entry : Map.of("2026-01-02", SubscriptionStatus.WAITING, "2026-01-01", SubscriptionStatus.ACTIVE,
                "2025-01-01", SubscriptionStatus.EXPIRED).entrySet()) {
            UUID documentId = createSubscription(Map.of("client", clientId.toString(), "start", entry.getKey(),
                    "lines", List.of(tariffLine(tariffId, 1))));
            commands.post(subscriptionDescriptor, documentId, ADMIN);
            assertThat(readSubscription(documentId).getStatus()).isEqualTo(entry.getValue());
            assertThat(readSubscription(documentId).isPosted()).isTrue();
        }
    }

    @Test
    void lateCreateAndUpdateFailuresRollbackEntireAggregate() {
        UUID tariffId = createTariff("100", 30, true);
        long headers = rowCount(subscriptionDescriptor.tableName()), rows = rowCount(faults.rows);
        faults.failSecondLine = true;

        assertThatThrownBy(() -> createSubscription(Map.of("client", clientId.toString(), "lines", List.of(tariffLine(tariffId,
                1), tariffLine(tariffId, 2))))).isInstanceOf(RuntimeException.class);

        assertThat(faults.lineCalls.get()).isEqualTo(2);
        assertThat(rowCount(subscriptionDescriptor.tableName())).isEqualTo(headers);
        assertThat(rowCount(faults.rows)).isEqualTo(rows);
        faults.clear();
        UUID documentId = createSubscription(tariffId, 1);
        var beforeUpdate = readSubscription(documentId);
        faults.failSecondLine = true;

        assertThatThrownBy(() -> commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(tariffLine(tariffId,
                2), tariffLine(tariffId, 3))), ADMIN)).isInstanceOf(RuntimeException.class);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo(beforeUpdate.getTotal());
        assertThat(readSubscription(documentId).getLines()).hasSize(1);
    }

    @Test
    void failureAfterBalanceMovementRollsBackBothRegistersAndPosted() {
        postPayment("1000");
        UUID documentId = createSubscription(createTariff("100", 30, true), 1);
        faults.target = documentId;
        faults.failRevenue = true;

        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, documentId, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThat(faults.observedFirstMovement)
                .as("balance movement visible in transaction before injected revenue failure").isTrue();
        assertThat(readSubscription(documentId).isPosted()).isFalse();
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();

        assertThat(balance(clientId)).isEqualByComparingTo("1000");
    }

    @Test
    void failedCancelledSaveRestoresPostingStatusAndBothRegisters() {
        postPayment("1000");
        UUID documentId = createSubscription(createTariff("100", 30, true), 1);
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        faults.target = documentId;
        faults.failStatus = true;

        assertThatThrownBy(() -> operations.cancel(documentId, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThat(faults.observedUnpost)
                .as("unpost visible in transaction before injected cancellation failure").isTrue();
        assertThat(readSubscription(documentId).isPosted()).isTrue();
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(balance(clientId)).isEqualByComparingTo("900");
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isEqualTo(1);
        assertThat(activeMovementCount(TariffRevenue.class, documentId)).isEqualTo(1);
    }

    @Test
    void concurrentExpensesCannotOverspend() throws Exception {
        postPayment("100");
        UUID tariffId = createTariff("60", 30, true), firstSubscriptionId = createSubscription(tariffId,
                1), secondSubscriptionId = createSubscription(tariffId, 1);
        faults.balanceBarrier = new CyclicBarrier(2);
        List<Boolean> result = runConcurrently(() -> commands.post(subscriptionDescriptor, firstSubscriptionId,
                ADMIN), () -> commands.post(subscriptionDescriptor, secondSubscriptionId, ADMIN));

        assertThat(result).containsExactlyInAnyOrder(true, false);
        assertThat(balance(clientId)).isEqualByComparingTo("40");
        assertThat(faults.barrierArrivals.get()).as("both writers reached the balance totals barrier").isEqualTo(2);
        assertThat(activeMovementCount(AccountBalance.class, firstSubscriptionId) + activeMovementCount(AccountBalance.class,
                secondSubscriptionId)).isEqualTo(1);

        assertThat(activeMovementCount(TariffRevenue.class, firstSubscriptionId) + activeMovementCount(TariffRevenue.class,
                secondSubscriptionId)).isEqualTo(1);
    }

    @Test
    void concurrentSameDocumentPostHasOneEffect() throws Exception {
        postPayment("100");
        UUID documentId = createSubscription(createTariff("60", 30, true), 1);

        assertThat(runConcurrently(() -> commands.post(subscriptionDescriptor, documentId, ADMIN), () -> commands.post(subscriptionDescriptor,
                documentId, ADMIN))).containsOnly(true);

        assertThat(balance(clientId)).isEqualByComparingTo("40");
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isEqualTo(1);
        assertThat(activeMovementCount(TariffRevenue.class, documentId)).isEqualTo(1);
    }

    @Test
    void postCancelRaceHasConsistentFinalState() throws Exception {
        postPayment("100");
        UUID documentId = createSubscription(createTariff("60", 30, true), 1);
        runConcurrently(() -> commands.post(subscriptionDescriptor, documentId, ADMIN), () -> operations.cancel(documentId,
                ADMIN));

        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(readSubscription(documentId).isPosted()).isFalse();
        assertThat(balance(clientId)).isEqualByComparingTo("100");
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();
    }
}
