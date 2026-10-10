package org.example.subscriptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
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
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import su.onno.access.AccessSubject;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.posting.RegisterPersistence;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.UiProperties;


@SpringBootTest(properties = {
    "onno.auth.users[0].username=admin", "onno.auth.users[0].password=admin", "onno.auth.users[0].roles[0]=ADMIN",
            "onno.auth.users[1].username=guest", "onno.auth.users[1].password=guest", "onno.auth.users[1].roles[0]=VIEWER"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DocumentFixture.TestSupport.class)
abstract class DocumentFixture {
    static final AccessSubject ADMIN = AccessSubject.user("admin", Set.of("ADMIN"));
    @Autowired
    DocumentCommandService commands;
    @Autowired
    CatalogCommandService catalogs;
    @Autowired
    DocumentOperations operations;
    @Autowired
    MetadataRegistry metadata;
    @Autowired
    Jdbi jdbi;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SubscriptionReader subscriptions;
    @Autowired
    DocumentFaults faults;
    @Autowired
    UiProperties properties;
    UUID clientId;
    DocumentDescriptor subscriptionDescriptor, payment;

    @BeforeEach
    void setup() {
        faults.clear();
        jdbi.setSqlLogger(faults);
        subscriptionDescriptor = metadata.getDocumentDescriptor(Subscription.class);
        payment = metadata.getDocumentDescriptor(Payment.class);
        faults.rows = subscriptionDescriptor.tabularSections().getFirst().tableName();
        faults.revenue = metadata.getRegisterDescriptor(TariffRevenue.class).tableName();
        faults.cash = metadata.getRegisterDescriptor(AccountBalance.class).tableName();
        faults.header = subscriptionDescriptor.tableName();
        clientId = createClient();
    }

    @AfterEach
    void cleanup() {
        faults.clear();
        jdbi.setSqlLogger(SqlLogger.NOP_SQL_LOGGER);
    }

    UUID createClient() {
        return (UUID) catalogs.create(metadata.getCatalogDescriptor(Client.class), new HashMap<>(Map.of("description",
                "Test " + UUID.randomUUID())), ADMIN).get("_id");
    }

    UUID createTariff(String price, int days, boolean available) {
        return (UUID) catalogs.create(metadata.getCatalogDescriptor(Tariff.class), new HashMap<>(Map.of("description",
                "Tariff " + UUID.randomUUID(),
                "price", price, "periodDays", days, "available", available)), ADMIN).get("_id");
    }

    Map<String, Object> tariffLine(UUID tariffId, int periods) {
        return new HashMap<>(Map.of("tariff", tariffId.toString(), "periods", periods));
    }

    Map<String, Object> savedTariffLine(UUID id, int index, int periods) {
        var old = readSubscription(id).getLines().get(index);
        var row = tariffLine(old.getTariff().id(), periods);
        row.put("snapshotKey", old.getSnapshotKey());
        return row;
    }

    UUID createSubscription(UUID tariffId, int periods) {
        return createSubscription(Map.of("client", clientId.toString(), "lines", List.of(tariffLine(tariffId,
                periods))));
    }

    UUID createSubscription(Map<String, Object> body) {
        return (UUID) commands.create(subscriptionDescriptor, new HashMap<>(body), ADMIN).get("_id");
    }

    UUID postPayment(String amount) {
        UUID id = (UUID) commands.create(payment, new HashMap<>(Map.of("client", clientId.toString(), "amount",
                amount)), ADMIN).get("_id");
        commands.post(payment, id, ADMIN);
        return id;
    }

    BigDecimal balance(UUID who) {
        var persistence = new RegisterPersistence<AccountBalance>(jdbi, metadata.getRegisterDescriptor(AccountBalance.class));
        var rows = persistence.getBalanceTyped(Map.of("client", su.onno.types.Ref.of(Client.class, who)));
        return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst().getAmount();
    }

    long activeMovementCount(Class<?> type, UUID id) {
        String table = metadata.getRegisterDescriptor(type).tableName();
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE _document_ref=:id AND _active=TRUE")
                .bind("id", id).mapTo(Long.class).one());
    }

    long rowCount(String table) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + table).mapTo(Long.class).one());
    }

    Subscription readSubscription(UUID id) {
        return subscriptions.findById(id).orElseThrow();
    }

    @TestConfiguration static class TestSupport {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneId.of("Europe/Moscow"));
        }
        @Bean
        DocumentFaults faults() {
            return new DocumentFaults();
        }
    }
}
