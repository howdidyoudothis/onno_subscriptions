package org.example.subscriptions.model;

import java.math.BigDecimal;
import java.util.List;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

@Document(name = "Payments", title = "Платежи", numberPrefix = "PAY-")
@AccessControl(readRoles = {
    "ADMIN"
}, writeRoles = {
    "ADMIN"
})
public class Payment extends DocumentObject implements Validated, BeforeWriteHandler, Postable {
    @Attribute(displayName = "Клиент")
    private Ref<Client> client;
    @Attribute(displayName = "Сумма", precision = 15, scale = 2)
    private BigDecimal amount;
    @Attribute(displayName = "Способ оплаты")
    private PaymentMethod method = PaymentMethod.CARD;

    public Ref<Client> getClient() {
        return client;
    }

    public void setClient(Ref<Client> client) {
        this.client = client;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public void setMethod(PaymentMethod method) {
        this.method = method;
    }

    @Override
    public void beforeWrite() {
        if (Money.valid(amount)) amount = Money.exact(amount);
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(BusinessRule.onField("client", "Укажите клиента", () -> client != null),
                BusinessRule.onField("amount", "Введите положительную сумму с точностью до копейки", () -> Money.valid(amount)),
                BusinessRule.onField("method", "Укажите способ оплаты", () -> method != null));
    }

    @Override
    public void handlePosting(PostingContext context) {
        context.movements(AccountBalance.class).addReceipt(row -> {
            row.setClient(client);
            row.setAmount(amount);
        });
    }
}
