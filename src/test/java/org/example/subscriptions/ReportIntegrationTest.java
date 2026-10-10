package org.example.subscriptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.Payment;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.Tariff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:reports04;DB_CLOSE_DELAY=-1",
    "onno.auth.users[0].username=admin", "onno.auth.users[0].password=admin", "onno.auth.users[0].roles[0]=ADMIN",
            "onno.auth.users[1].username=viewer", "onno.auth.users[1].password=viewer", "onno.auth.users[1].roles[0]=VIEWER"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StatusFixture.Support.class)
class ReportIntegrationTest extends StatusFixture {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    ReportHttpSupport http;

    @BeforeEach
    void setupHttp() {
        http = new ReportHttpSupport(mockMvc, objectMapper);
    }

    Map<String, String> period(String from, String to) {
        return Map.of("from", from, "to", to, "client", clientId.toString());
    }

    void revenue(JsonNode rows, UUID tariffId, String amount, int periods) {
        JsonNode row = null;
        for (var value : rows) if (value.path("tariff").asText().equals(tariffId.toString()) && value.path("client").asText()
                .equals(clientId.toString())) row = value;

        assertThat(row).as("revenue row for client %s and tariff %s", clientId, tariffId).isNotNull();
        assertThat(row.path("amount").decimalValue()).as("revenue amount for tariff %s", tariffId)
                .isEqualByComparingTo(amount);
        assertThat(row.path("periods").intValue()).as("period count for tariff %s", tariffId).isEqualTo(periods);
    }

    @Test
    void multipleDocumentsClientsPeriodCancelAndExpiryThroughHttp() throws Exception {
        var session = http.login();
        catalogs.update(metadata.getCatalogDescriptor(Tariff.class), tariffId, Map.of("periodDays", 30),
                ADMIN);
        UUID tariffBId = (UUID) catalogs.create(metadata.getCatalogDescriptor(Tariff.class), Map.of("description",
                "B", "price", "250", "periodDays", 365, "available", true), ADMIN).get("_id");
        UUID primarySubscriptionId = (UUID) commands.create(subscriptionDescriptor, Map.of("client", clientId.toString(),
                "lines", List.of(Map.of("tariff", tariffId.toString(), "periods", 2), Map.of("tariff",
                tariffBId.toString(), "periods", 1))), ADMIN).get("_id");
        commands.post(subscriptionDescriptor, primarySubscriptionId, ADMIN);

        assertThat(http.getJson(session, "/api/registers/AccountBalances/balance", Map.of("client", clientId.toString()))
                .get(0).path("amount").decimalValue()).isEqualByComparingTo("550");
        UUID extraSubscriptionId = createSubscription("2025-01-01", 1);
        commands.update(subscriptionDescriptor, extraSubscriptionId, Map.of("date", "2026-01-02T12:00:00"),
                ADMIN);
        commands.post(subscriptionDescriptor, extraSubscriptionId, ADMIN);

        assertThat(readSubscription(extraSubscriptionId).getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(readSubscription(extraSubscriptionId).isPosted()).isTrue();
        UUID other = (UUID) catalogs.create(metadata.getCatalogDescriptor(Client.class), Map.of("description",
                "Other report client"), ADMIN).get("_id");
        UUID paymentId = (UUID) commands.create(metadata.getDocumentDescriptor(Payment.class), Map.of("client",
                other.toString(), "amount", "500"), ADMIN).get("_id");
        commands.post(metadata.getDocumentDescriptor(Payment.class), paymentId, ADMIN);
        UUID otherSubscriptionId = (UUID) commands.create(subscriptionDescriptor, Map.of("client", other.toString(),
                "lines", List.of(Map.of("tariff", tariffId.toString(), "periods", 1))), ADMIN).get("_id");
        commands.post(subscriptionDescriptor, otherSubscriptionId, ADMIN);
        var all = period("2026-01-01T00:00:00", "2026-01-02T23:59:59");
        var totals = http.getJson(session, "/api/registers/TariffRevenues/turnover", all);

        assertThat(totals.size()).isEqualTo(2);
        revenue(totals, tariffId, "300", 3);
        revenue(totals, tariffBId, "250", 1);

        assertThat(http.getJson(session, "/api/divkit/revenue-report", Map.of()).toString()).contains("Отчёт по выручке",
                "Сформировать");
        var beforeLedger = ledgerSnapshot();
        var beforePrimary = financialState(primarySubscriptionId);
        var beforeExtra = financialState(extraSubscriptionId);
        var report = http.reportJson(session, "2026-01-01", "2026-01-02", clientId.toString());

        assertThat(report.path("message").asText()).contains("01.01.2026", "02.01.2026");
        assertThat(report.path("message").asText()).contains(http.getJson(session, "/api/catalogs/Clients/" + clientId,
                Map.of()).path("description").asText()).doesNotContain("null");

        assertThat(report.path("details").size()).isEqualTo(2);
        assertThat(report.path("details").toString()).contains("Сумма: 300.00 · Периоды: 3", "Сумма: 250.00 · Периоды: 1")
                .doesNotContain("Other report client");
        var allClients = http.reportJson(session, "2026-01-01", "2026-01-02", "").path("details");

        assertThat(allClients.toString()).contains("Other report client", "Сумма: 100.00 · Периоды: 1");
        assertThat(allClients.size()).isEqualTo(http.getJson(session, "/api/registers/TariffRevenues/turnover",
                Map.of("from", "2026-01-01T00:00:00", "to", "2026-01-02T23:59:59.999999")).size());

        assertThat(http.reportJson(session, "2026-01-01", "2026-01-02", clientId.toString())).isEqualTo(report);
        assertThat(ledgerSnapshot()).isEqualTo(beforeLedger);
        assertThat(financialState(primarySubscriptionId)).isEqualTo(beforePrimary);
        assertThat(financialState(extraSubscriptionId)).isEqualTo(beforeExtra);
        var exact = http.getJson(session, "/api/registers/TariffRevenues/turnover", period("2026-01-01T12:00:00",
                "2026-01-01T12:00:00"));
        revenue(exact, tariffId, "200", 2);
        revenue(exact, tariffBId, "250", 1);

        assertThat(http.getJson(session, "/api/registers/TariffRevenues/turnover", period("2026-01-03T00:00:00",
                "2026-01-04T00:00:00")).size()).isZero();

        assertThat(http.reportJson(session, "2026-01-03", "2026-01-04", clientId.toString()).path("details").get(0).asText())
                .isEqualTo("Нет выручки за выбранный период");
        var feed = http.getJson(session, "/api/list/registers/TariffRevenues/movements", Map.of("eq", "client," + clientId,
                "ge", "_period,2026-01-01T00:00:00", "le", "_period,2026-01-02T23:59:59"));

        assertThat(feed.path("total").intValue()).isEqualTo(3);
        assertThat(feed.path("rows").size()).isEqualTo(3);
        for (var row : feed.path("rows")) assertThat(row.path("client").asText()).isEqualTo(clientId.toString());
        clock.day("2027-01-01");
        task.execute();

        assertThat(readSubscription(primarySubscriptionId).getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        var expired = http.getJson(session, "/api/registers/TariffRevenues/turnover", all);

        assertThat(expired).isEqualTo(totals);
        assertThat(http.reportJson(session, "2026-01-01", "2026-01-02", clientId.toString())).isEqualTo(report);
        operations.cancel(extraSubscriptionId, ADMIN);
        var after = http.getJson(session, "/api/registers/TariffRevenues/turnover", all);
        revenue(after, tariffId, "200", 2);
        revenue(after, tariffBId, "250", 1);

        assertThat(http.reportJson(session, "2026-01-01", "2026-01-02", clientId.toString()).path("details").toString())
                .contains("Сумма: 200.00 · Периоды: 2").doesNotContain("300.00");
        assertThat(http.getJson(session, "/api/registers/AccountBalances/balance", Map.of("client", clientId.toString()))
                .get(0).path("amount").decimalValue()).isEqualByComparingTo("550");
        assertThat(http.getJson(session, "/api/registers/AccountBalances/balance", Map.of("client", other.toString())).get(0)
                .path("amount").decimalValue()).isEqualByComparingTo("400");
        var balanceFeed = http.getJson(session, "/api/list/registers/AccountBalances/balance", Map.of("eq",
                "client," + clientId));

        assertThat(balanceFeed.path("total").intValue()).isEqualTo(1);
        assertThat(balanceFeed.path("rows").get(0).path("amount").decimalValue()).isEqualByComparingTo("550");
    }

    @Test
    void inclusiveWholeDaysAndInvalidRange() throws Exception {
        var session = http.login();
        for (String date : List.of("2025-12-31T23:59:59.999999", "2026-01-01T00:00:00", "2026-01-02T23:59:59.999999",
                "2026-01-03T00:00:00")) {
            UUID documentId = createSubscription("2026-01-01", 1);
            commands.update(subscriptionDescriptor, documentId, Map.of("date", date), ADMIN);
            commands.post(subscriptionDescriptor, documentId, ADMIN);
        }
        var report = http.reportJson(session, "2026-01-01", "2026-01-02", clientId.toString());

        assertThat(report.path("details").size()).isEqualTo(1);
        assertThat(report.path("details").get(0).asText()).contains("Сумма: 200.00 · Периоды: 2");
        var stock = http.getJson(session, "/api/registers/TariffRevenues/turnover", period("2026-01-01T00:00:00",
                "2026-01-02T23:59:59.999999"));
        revenue(stock, tariffId, "200", 2);
        var invalid = http.report(session, "2026-01-03", "2026-01-01", "");

        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        var error = objectMapper.readTree(invalid.getResponse().getContentAsString());

        assertThat(error.path("message").asText()).contains("Дата начала");
        assertThat(error.path("keepFormOpen").asBoolean()).isTrue();
        assertThat(error.path("fieldErrors").path("to").size()).isEqualTo(1);
        assertThat(http.report(session, "bad", "2026-01-01", "").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void anonymousAndAuthenticatedNonAdminCannotRunReport() throws Exception {
        var anonymousCsrf = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        var anonymousToken = objectMapper.readTree(anonymousCsrf.getResponse().getContentAsString());

        assertThat(mockMvc.perform(post("/api/divkit/page-action").param("route", "/revenue-report").param("key",
                "generate").cookie(anonymousCsrf.getResponse().getCookie("XSRF-TOKEN")).header(anonymousToken.path("headerName")
                .asText(), anonymousToken.path("token").asText()).contentType("application/json").content("{}")).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
        var logged = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"viewer\",\"password\":\"viewer\",\"remember\":false}"))
                .andReturn();
        assertThat(logged.getResponse().getStatus()).isEqualTo(200);
        var viewer = (MockHttpSession) logged.getRequest().getSession(false);

        assertThat(http.report(viewer, "2026-01-01", "2026-01-02", "").getResponse().getStatus()).isEqualTo(403);
    }
}
