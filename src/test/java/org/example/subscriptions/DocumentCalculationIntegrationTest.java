package org.example.subscriptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.subscriptions.model.AccountBalance;
import org.example.subscriptions.model.Payment;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.Tariff;
import org.example.subscriptions.model.TariffRevenue;
import org.example.subscriptions.service.DocumentOperations;
import org.example.subscriptions.service.SubscriptionReader;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentCalculationIntegrationTest extends DocumentFixture {
    @Test
    void realProxyAndReadOnlyRepositorySurface() {
        assertThat(AopUtils.isCglibProxy(commands)).isTrue();
        assertThat(Arrays.stream(SubscriptionReader.class.getMethods()).map(m -> m.getName())).doesNotContain("save",
                "delete");
        UUID documentId = createSubscription(createTariff("100", 30, true), 1);

        assertThat(readSubscription(documentId).getLines()).hasSize(1);
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void draftDefaultsTrustedSnapshotsAndRecalculation() {
        UUID tariffAId = createTariff("100", 30, true), tariffBId = createTariff("250", 365, true);
        var forged = tariffLine(tariffAId, 2);
        forged.put("price", "0.01");
        forged.put("snapshotPeriodDays", 1);
        forged.put("amount", "0.02");
        UUID documentId = createSubscription(Map.of("client", clientId.toString(), "lines", List.of(forged,
                tariffLine(tariffBId, 1)), "total", "1", "end", "2026-01-02"));
        var subscription = readSubscription(documentId);

        assertThat(subscription.getDate()).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));
        assertThat(subscription.getStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(subscription.getTotal()).isEqualByComparingTo("450");
        assertThat(subscription.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(subscription.getLines().getFirst().getPrice()).isEqualByComparingTo("100");
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        assertThat(subscription.isPosted()).isFalse();
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();
        commands.update(subscriptionDescriptor, documentId, Map.of("start", "2026-02-01", "lines", List.of(tariffLine(tariffAId,
                3))), ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("300");
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2026, 5, 2));
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(tariffLine(tariffBId,
                1))), ADMIN);

        assertThat(readSubscription(documentId).getLines().getFirst().getPrice()).isEqualByComparingTo("250");
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    void snapshotsSurviveMasterEditBeforeAndAfterPosting() {
        UUID tariffId = createTariff("100", 30, true), documentId = createSubscription(tariffId, 2);
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("price", "200", "periodDays",
                60), ADMIN);
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(savedTariffLine(documentId,
                0, 3))), ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("300");
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        postPayment("1000");
        commands.post(subscriptionDescriptor, documentId, ADMIN);
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("available", false),
                ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("300");
        assertThat(balance(clientId)).isEqualByComparingTo("700");
        operations.cancel(documentId, ADMIN);

        assertThat(balance(clientId)).isEqualByComparingTo("1000");
    }

    @Test
    void exactMoneyAndDateBoundaries() {
        UUID documentId = createSubscription(Map.of("client", clientId.toString(), "start", "2024-02-28",
                "date", "2024-02-28T13:14:15",
                "lines", List.of(tariffLine(createTariff("0.10", 1, true), 1), tariffLine(createTariff("0.20", 1,
                true), 1))));

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("0.30");
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(readSubscription(documentId).getDate()).isEqualTo(LocalDateTime.of(2024, 2, 28, 13, 14,
                15));

        assertThat(SubscriptionStatus.posted(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 1), LocalDate.of(2026,
                1, 1))).isEqualTo(SubscriptionStatus.WAITING);

        assertThat(SubscriptionStatus.posted(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026,
                1, 31))).isEqualTo(SubscriptionStatus.ACTIVE);

        assertThat(SubscriptionStatus.posted(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026,
                2, 1))).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    void rowSnapshotsSurviveRemovalReorderAndRejectForeignOrDuplicateKeys() {
        UUID tariffAId = createTariff("100", 30, true), tariffBId = createTariff("250", 365, true);
        UUID documentId = createSubscription(Map.of("client", clientId.toString(), "lines", List.of(tariffLine(tariffAId,
                1), tariffLine(tariffBId, 1))));
        var first = savedTariffLine(documentId, 0, 2);
        var second = savedTariffLine(documentId, 1, 2);
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffBId, Map.of("price", "500", "periodDays",
                730), ADMIN);
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(second, first)), ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("700");
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(second)), ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("500");
        assertThat(readSubscription(documentId).getEndDate()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThatThrownBy(() -> commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(second,
                second)), ADMIN)).isInstanceOf(RuntimeException.class);
        var foreign = tariffLine(tariffBId, 1);
        foreign.put("snapshotKey", UUID.randomUUID().toString());

        assertThatThrownBy(() -> commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(foreign)),
                ADMIN)).isInstanceOf(RuntimeException.class);
        // A genuinely new row with the same tariff takes the current master, not another row's history.
        commands.update(subscriptionDescriptor, documentId, Map.of("lines", List.of(second, tariffLine(tariffBId,
                1))), ADMIN);

        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("1000");
        assertThat(readSubscription(documentId).getLines().get(1).getSnapshotPeriodDays()).isEqualTo(730);
    }

    @Test
    void invalidAmountsAndTariffNumbersAreRejectedByRules() {
        for (String amount : List.of("0", "-1", "1.001", "10000000000000")) {
            assertThatThrownBy(() -> commands.create(payment, Map.of("client", clientId.toString(), "amount",
                    amount), ADMIN)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> createTariff(amount, 30, true)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> commands.create(payment, Map.of("client", clientId.toString()), ADMIN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createTariff("100", 0, true)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createTariff("100", - 1, true)).isInstanceOf(RuntimeException.class);
        var emptyPayment = new Payment();

        assertThat(emptyPayment.rules()).anyMatch(rule -> !rule.holds());
        var emptyTariff = new Tariff();

        assertThat(emptyTariff.rules()).allMatch(rule -> !rule.holds());
    }

    @Test
    void requiredReferencesAvailabilityPeriodsAndOverflow() {
        UUID tariffId = createTariff("100", 30, true);

        assertThatThrownBy(() -> createSubscription(Map.of("lines", List.of(tariffLine(tariffId, 1))))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createSubscription(Map.of("client", clientId.toString(), "lines", List.of()))).isInstanceOf(RuntimeException.class);
        for (int invalidPeriods : List.of(0, - 1)) assertThatThrownBy(() -> createSubscription(tariffId,
                invalidPeriods)).isInstanceOf(RuntimeException.class);
        for (Object invalidPeriods : List.of("1.5", new BigDecimal("1.5"), "2147483648", new BigDecimal("4294967297"))) {
            assertThatThrownBy(() -> createSubscription(Map.of("client", clientId.toString(), "lines", List.of(Map.of("tariff",
                    tariffId.toString(), "periods", invalidPeriods))))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("periodDays",
                    invalidPeriods), ADMIN)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> createSubscription(createTariff("100", 30, false), 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createSubscription(UUID.randomUUID(), 1)).isInstanceOf(RuntimeException.class);
        catalogs.delete(metadata.getCatalogDescriptor(Tariff.class), tariffId, ADMIN);

        assertThatThrownBy(() -> createSubscription(tariffId, 1)).isInstanceOf(RuntimeException.class);
        UUID huge = createTariff("9999999999999.99", Integer.MAX_VALUE, true);

        assertThatThrownBy(() -> createSubscription(huge, 2)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createSubscription(Map.of("client", clientId.toString(), "start", "9999-12-31",
                "lines", List.of(tariffLine(createTariff("1", Integer.MAX_VALUE, true), Integer.MAX_VALUE))))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unavailableBeforePostFailsWithoutRepricingOrMovements() {
        UUID tariffId = createTariff("100", 30, true), documentId = createSubscription(tariffId, 1);
        postPayment("1000");
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("available", false),
                ADMIN);

        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, documentId, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThat(balance(clientId)).isEqualByComparingTo("1000");
        assertThat(readSubscription(documentId).isPosted()).isFalse();
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();
    }

    @Test
    void validationOnExistingDraftNeverWritesIncludingCancellation() {
        UUID tariffId = createTariff("100", 30, true), documentId = createSubscription(tariffId, 1);
        var beforeUpdate = readSubscription(documentId);
        var report = commands.validate(subscriptionDescriptor, documentId, Map.of("lines", List.of(tariffLine(tariffId,
                3))), ADMIN);

        assertThat(report.get("valid")).isEqualTo(true);
        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("100");
        assertThat(readSubscription(documentId).getVersion()).isEqualTo(beforeUpdate.getVersion());
        var cancel = commands.validate(subscriptionDescriptor, documentId, Map.of("status", DocumentOperations.enumId(SubscriptionStatus.CANCELLED)),
                ADMIN);

        assertThat(cancel.get("valid")).isEqualTo(false);
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();
    }
}
