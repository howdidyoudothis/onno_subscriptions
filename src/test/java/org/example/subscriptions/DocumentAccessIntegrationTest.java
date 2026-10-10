package org.example.subscriptions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.example.subscriptions.model.AccountBalance;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.ClientStatus;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.TariffRevenue;
import org.example.subscriptions.service.DocumentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class DocumentAccessIntegrationTest extends DocumentFixture {
    DocumentHttpSupport http;

    @BeforeEach
    void setupHttp() {
        http = new DocumentHttpSupport(mockMvc, objectMapper);
    }

    @Test
    void httpDraftSaveCalculatedValuesSeparatePostBalanceTurnoverAndCancel() throws Exception {
        var adminSession = http.login("admin");
        UUID tariffAId = createTariff("100", 30, true), tariffBId = createTariff("250", 365, true);
        var receipt = http.object(http.request(adminSession, post("/api/documents/Payments"), Map.of("client",
                clientId.toString(), "amount", "1000")));
        UUID receiptId = UUID.fromString(receipt.get("id").toString());

        assertThat(balance(clientId)).isEqualByComparingTo("0");
        http.object(http.request(adminSession, post("/api/actions/documents/Payments/postSaved").param("id",
                receiptId.toString()), Map.of()));
        var draft = http.object(http.request(adminSession, post("/api/documents/Subscriptions"), Map.of("client",
                clientId.toString(), "lines", List.of(tariffLine(tariffAId, 2), tariffLine(tariffBId, 1)))));
        UUID documentId = UUID.fromString(draft.get("id").toString());

        assertThat(new BigDecimal(draft.get("total").toString())).isEqualByComparingTo("450");
        assertThat(draft.get("endDate")).isEqualTo("2027-01-01");
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        assertThat(balance(clientId)).isEqualByComparingTo("1000");
        assertThat(activeMovementCount(TariffRevenue.class, documentId)).isZero();
        var changed = http.object(http.request(adminSession, put("/api/documents/Subscriptions/" + documentId),
                Map.of("lines", List.of(tariffLine(tariffBId, 2)))));

        assertThat(new BigDecimal(changed.get("total").toString())).isEqualByComparingTo("500");
        assertThat(balance(clientId)).isEqualByComparingTo("1000");
        assertThat(readSubscription(documentId).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        http.object(http.request(adminSession, put("/api/documents/Subscriptions/" + documentId), Map.of("lines",
                List.of(tariffLine(tariffAId, 2), tariffLine(tariffBId, 1)))));
        http.object(http.request(adminSession, post("/api/actions/documents/Subscriptions/postSaved").param("id",
                documentId.toString()), Map.of()));
        var repeats = http.object(http.request(adminSession, post("/api/actions/documents/Subscriptions/postSaved/batch"),
                Map.of("ids", List.of(documentId.toString(), documentId.toString()))));

        assertThat(((Number) repeats.get("ok")).intValue()).isPositive();
        assertThat(balance(clientId)).isEqualByComparingTo("550");
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isEqualTo(1);
        UUID firstClient = clientId, other = createClient();
        clientId = other;
        postPayment("500");
        UUID otherSubscriptionId = createSubscription(tariffAId, 1);
        commands.post(subscriptionDescriptor, otherSubscriptionId, ADMIN);
        clientId = firstClient;

        assertThat(balance(other)).isEqualByComparingTo("400");
        UUID tooMuch = createSubscription(tariffBId, 10);
        var refused = http.request(adminSession, post("/api/actions/documents/Subscriptions/postSaved").param("id",
                tooMuch.toString()), Map.of());

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("Недостаточно средств");
        assertThat(readSubscription(tooMuch).isPosted()).isFalse();
        assertThat(readSubscription(tooMuch).getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
        assertThat(activeMovementCount(AccountBalance.class, tooMuch) + activeMovementCount(TariffRevenue.class,
                tooMuch)).isZero();

        assertThat(balance(clientId)).isEqualByComparingTo("550");
        var balances = http.request(adminSession, get("/api/registers/AccountBalances/balance").param("client",
                clientId.toString()), null);

        assertThat(balances.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(balances.getResponse().getContentAsString()).get(0).path("amount").decimalValue())
                .isEqualByComparingTo("550");
        var turnover = http.request(adminSession, get("/api/registers/TariffRevenues/turnover").param("from",
                "2026-01-01T00:00:00").param("to", "2026-12-31T23:59:59").param("client", clientId.toString()), null);

        assertThat(turnover.getResponse().getStatus()).isEqualTo(200);
        var rows = objectMapper.readTree(turnover.getResponse().getContentAsString());

        assertThat(rows.size()).isEqualTo(2);
        for (var row : rows) {
            boolean isA = row.path("tariff").asText().equals(tariffAId.toString());
            assertThat(row.path("amount").decimalValue()).isEqualByComparingTo(isA ? "200" : "250");
            assertThat(row.path("periods").decimalValue()).isEqualByComparingTo(isA ? "2" : "1");
        }
        http.object(http.request(adminSession, post("/api/actions/documents/Subscriptions/cancel").param("id",
                documentId.toString()), Map.of()));
        var cancelledBatch = http.object(http.request(adminSession, post("/api/actions/documents/Subscriptions/cancel/batch"),
                Map.of("ids", List.of(documentId.toString()))));

        assertThat(((Number) cancelledBatch.get("ok")).intValue()).isEqualTo(1);
        assertThat(balance(clientId)).isEqualByComparingTo("1000");
        assertThat(activeMovementCount(TariffRevenue.class, documentId)).isZero();
        var cancelledTurnover = http.request(adminSession, get("/api/registers/TariffRevenues/turnover").param("from",
                "2026-01-01T00:00:00").param("to", "2026-12-31T23:59:59").param("client", clientId.toString()), null);

        assertThat(objectMapper.readTree(cancelledTurnover.getResponse().getContentAsString()).size()).isZero();
        assertThat(balance(other)).isEqualByComparingTo("400");
    }

    @Test
    void httpGuardsValidationPreviewDeleteBatchAndPermissions() throws Exception {
        var adminSession = http.login("admin");
        UUID tariffId = createTariff("100", 30, true), documentId = createSubscription(tariffId, 1);
        postPayment("1000");
        long before = rowCount(subscriptionDescriptor.tableName());
        var valid = http.object(http.request(adminSession, post("/api/documents/Subscriptions/validate"),
                Map.of("client", clientId.toString(), "lines", List.of(tariffLine(tariffId, 1)))));

        assertThat(valid.get("valid")).isEqualTo(true);
        assertThat(rowCount(subscriptionDescriptor.tableName())).isEqualTo(before);
        var preview = http.request(adminSession, get("/api/documents/Subscriptions/" + documentId + "/posting-preview"),
                null);

        assertThat(preview.getResponse().getStatus()).isEqualTo(200);
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isZero();
        assertThat(readSubscription(documentId).isPosted()).isFalse();
        http.object(http.request(adminSession, post("/api/documents/Subscriptions/" + documentId + "/post"),
                Map.of()));
        var postedPreview = http.request(adminSession, get("/api/documents/Subscriptions/" + documentId + "/posting-preview"),
                null);

        assertThat(postedPreview.getResponse().getStatus()).isEqualTo(200);
        assertThat(activeMovementCount(AccountBalance.class, documentId)).isEqualTo(1);
        assertThat(balance(clientId)).isEqualByComparingTo("900");
        for (var body : List.of(Map.of("total", "1"), Map.of("client", createClient().toString()), Map.of("status",
                DocumentOperations.enumId(SubscriptionStatus.DRAFT).toString()), Map.of("_posted", false), Map.of("posted",
                false), Map.of("_version", 0, "lines", List.of(tariffLine(tariffId, 2))))) {
            var result = http.request(adminSession, put("/api/documents/Subscriptions/" + documentId), body);
            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(readSubscription(documentId).getTotal()).isEqualByComparingTo("100");
        assertThat(balance(clientId)).isEqualByComparingTo("900");
        var removed = http.request(adminSession, delete("/api/documents/Subscriptions/" + documentId), null);

        assertThat(removed.getResponse().getStatus()).isBetween(400, 499);
        var batch = http.object(http.request(adminSession, post("/api/documents/Subscriptions/batch-delete"),
                Map.of("ids", List.of(documentId.toString()))));

        assertThat(batch.get("ok")).isEqualTo(0);
        var denied = http.request(http.login("guest"), post("/api/documents/Subscriptions/" + documentId + "/post"),
                Map.of());

        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(balance(clientId)).isEqualByComparingTo("900");
        http.object(http.request(adminSession, post("/api/actions/documents/Subscriptions/unpostSaved").param("id",
                documentId.toString()), Map.of()));
        var deleted = http.request(adminSession, delete("/api/documents/Subscriptions/" + documentId), null);

        assertThat(deleted.getResponse().getStatus()).isEqualTo(200);
        assertThat(readSubscription(documentId).isDeletionMark()).isTrue();
        assertThat(activeMovementCount(AccountBalance.class, documentId) + activeMovementCount(TariffRevenue.class,
                documentId)).isZero();
    }

    @Test
    void paymentGuardsClientStateAndGlobalReadOnlyRepeat() {
        catalogs.update(metadata.getCatalogDescriptor(Client.class), clientId, Map.of("status", DocumentOperations.enumId(ClientStatus.INACTIVE)),
                ADMIN);
        UUID receipt = postPayment("100");

        assertThatThrownBy(() -> commands.update(payment, receipt, Map.of("amount", "200"), ADMIN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> commands.delete(payment, receipt, ADMIN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> commands.post(payment, receipt, AccessSubject.user("guest", Set.of("VIEWER")))).isInstanceOf(RuntimeException.class);
        assertThat(balance(clientId)).isEqualByComparingTo("100");
        properties.setReadOnly(true);
        try {
            assertThatThrownBy(() -> commands.post(payment, receipt, ADMIN)).isInstanceOf(RuntimeException.class);
        } finally {
            properties.setReadOnly(false);
        }
        catalogs.delete(metadata.getCatalogDescriptor(Client.class), clientId, ADMIN);

        assertThatThrownBy(() -> commands.create(payment, Map.of("client", clientId.toString(), "amount",
                "1"), ADMIN)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void forgedSystemStateAndInternalPermissionDenial() {
        UUID tariffId = createTariff("100", 30, true);
        for (var forged : List.of(Map.of("status", DocumentOperations.enumId(SubscriptionStatus.ACTIVE)),
                Map.of("_posted", true), Map.of("deletionMark", true))) {
            var body = new HashMap<String, Object>();
            body.put("client", clientId.toString());
            body.put("lines", List.of(tariffLine(tariffId, 1)));
            body.putAll(forged);
            assertThatThrownBy(() -> createSubscription(body)).isInstanceOf(RuntimeException.class);
        }
        UUID documentId = createSubscription(tariffId, 1);
        postPayment("1000");
        commands.post(subscriptionDescriptor, documentId, ADMIN);

        assertThatThrownBy(() -> commands.post(subscriptionDescriptor, documentId, AccessSubject.user("guest",
                Set.of("VIEWER")))).isInstanceOf(RuntimeException.class);

        assertThat(balance(clientId)).isEqualByComparingTo("900");
    }
}
