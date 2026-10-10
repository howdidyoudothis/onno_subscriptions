package org.example.subscriptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.example.subscriptions.model.AccountBalance;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.Payment;
import org.example.subscriptions.model.Subscription;
import org.example.subscriptions.model.Tariff;
import org.example.subscriptions.model.TariffRevenue;
import org.example.subscriptions.service.DocumentOperations;
import org.example.subscriptions.service.SubscriptionReader;
import org.example.subscriptions.service.SubscriptionStatusTask;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import su.onno.access.AccessSubject;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.DocumentCommandService;

abstract class StatusFixture {
    static final AccessSubject ADMIN = AccessSubject.user("admin", Set.of("ADMIN"));
    @Autowired
    DocumentCommandService commands;
    @Autowired
    CatalogCommandService catalogs;
    @Autowired
    DocumentOperations operations;
    @Autowired
    SubscriptionStatusTask task;
    @Autowired
    SubscriptionReader reader;
    @Autowired
    MetadataRegistry metadata;
    @Autowired
    Jdbi jdbi;
    @Autowired
    PlatformTransactionManager manager;
    @Autowired
    MutableClock clock;
    UUID clientId, tariffId;
    DocumentDescriptor subscriptionDescriptor;

    @BeforeEach
    void seed() {
        clock.day("2026-01-01");
        subscriptionDescriptor = metadata.getDocumentDescriptor(Subscription.class);
        clientId = (UUID) catalogs.create(metadata.getCatalogDescriptor(Client.class), Map.of("description",
                "Status " + UUID.randomUUID()), ADMIN).get("_id");
        tariffId = (UUID) catalogs.create(metadata.getCatalogDescriptor(Tariff.class), Map.of("description",
                "Daily " + UUID.randomUUID(), "price", "100", "periodDays", 1, "available", true), ADMIN).get("_id");
        UUID pay = (UUID) commands.create(metadata.getDocumentDescriptor(Payment.class), Map.of("client",
                clientId.toString(), "amount", "1000"), ADMIN).get("_id");
        commands.post(metadata.getDocumentDescriptor(Payment.class), pay, ADMIN);
    }

    UUID createSubscription(String start, int periods) {
        return (UUID) commands.create(subscriptionDescriptor, Map.of("client", clientId.toString(), "start",
                start, "lines", List.of(Map.of("tariff", tariffId.toString(), "periods", periods))), ADMIN).get("_id");
    }

    Subscription readSubscription(UUID id) {
        return reader.findById(id).orElseThrow();
    }

    Map<String, Object> financialState(UUID id) {
        Map<String, Object> value = new LinkedHashMap<>(jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + subscriptionDescriptor.tableName() + " WHERE _id=:id")
                .bind("id", id).mapToMap().one()));
        value.remove("status");
        value.remove("_version");
        value.put("rows", jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + subscriptionDescriptor.tabularSections()
                .getFirst().tableName() + " WHERE _parent_id=:id ORDER BY _line_number").bind("id", id).mapToMap().list()));
        return value;
    }

    List<Object> ledgerSnapshot() {
        var values = new ArrayList<Object>();
        for (Class<?> type : List.of(AccountBalance.class, TariffRevenue.class)) {
            String table = metadata.getRegisterDescriptor(type).tableName();
            values.add(jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + table + " WHERE client=:id ORDER BY _id").bind("id",
                    clientId).mapToMap().list()));
            if (type == AccountBalance.class) values.add(jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + table + "_totals WHERE client=:id")
                    .bind("id", clientId).mapToMap().list()));
        }
        return values;
    }

    long activeMovementCount(UUID id) {
        long result = 0;
        for (Class<?> type : List.of(AccountBalance.class, TariffRevenue.class)) {
            String table = metadata.getRegisterDescriptor(type).tableName();
            result += jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE _document_ref=:id AND _active=TRUE")
                    .bind("id", id).mapTo(Long.class).one());
        }
        return result;
    }

    @TestConfiguration static class Support {
        @Bean
        @Primary
        MutableClock statusClock() {
            var value = new MutableClock();
            value.day("2026-01-01");
            return value;
        }
    }
}
