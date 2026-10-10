package org.example.subscriptions.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;
import su.onno.validation.ValidationException;

@Document(name = "Subscriptions", title = "Подписки", numberPrefix = "SUB-")
@AccessControl(readRoles = {
    "ADMIN"
}, writeRoles = {
    "ADMIN"
})
public class Subscription extends DocumentObject implements Validated, BeforeWriteHandler, Postable {
    @Attribute(displayName = "Клиент")
    private Ref<Client> client;
    @Attribute(displayName = "Статус")
    private SubscriptionStatus status = SubscriptionStatus.DRAFT;
    @Attribute(displayName = "Начало")
    private LocalDate start;
    @Attribute(displayName = "Окончание (не включительно)")
    private LocalDate endDate;
    @Attribute(displayName = "Итого", precision = 15, scale = 2)
    private BigDecimal total;
    @TabularSection(name = "lines")
    private List<SubscriptionLine> lines = new ArrayList<>();

    public Ref<Client> getClient() {
        return client;
    }

    public void setClient(Ref<Client> client) {
        this.client = client;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public LocalDate getStart() {
        return start;
    }

    public void setStart(LocalDate start) {
        this.start = start;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public List<SubscriptionLine> getLines() {
        return lines;
    }

    public void setLines(List<SubscriptionLine> lines) {
        this.lines = lines;
    }

    @Override
    public void beforeWrite() {
        if (start == null || lines == null || lines.isEmpty()) return;
        BigDecimal sum = BigDecimal.ZERO;
        long maxDays = 0;
        for (SubscriptionLine row : lines) {
            if (!validLine(row)) return;
            // BusinessRule gives the field-level failure.
            row.setPrice(Money.exact(row.getPrice()));
            row.setAmount(Money.exact(row.getPrice().multiply(BigDecimal.valueOf(row.getPeriods()))));
            sum = sum.add(row.getAmount());
            maxDays = Math.max(maxDays, Math.multiplyExact((long) row.getSnapshotPeriodDays(), row.getPeriods()));
        }
        total = Money.exact(sum);
        try {
            endDate = start.plusDays(maxDays);
        } catch (RuntimeException overflow) {
            throw new ValidationException("Срок подписки выходит за допустимые границы даты", "start");
        }
    }

    private static boolean validLine(SubscriptionLine row) {
        return row != null && row.getTariff() != null && row.getPeriods() != null && row.getPeriods() > 0
        && row.getSnapshotPeriodDays() != null && row.getSnapshotPeriodDays() > 0 && Money.valid(row.getPrice());
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(BusinessRule.onField("client", "Укажите клиента", () -> client != null),
                BusinessRule.onField("start", "Укажите дату начала", () -> start != null),
                new BusinessRule("lines", "Добавьте хотя бы один доступный тариф с положительным числом периодов",
                () -> lines != null && !lines.isEmpty() && lines.stream().allMatch(Subscription::validLine)),
                new BusinessRule("total", "Некорректный итог или срок подписки", () -> Money.valid(total) && endDate != null && start != null && endDate.isAfter(start)),
                new BusinessRule("state", "Отменённую подписку нельзя проводить; черновик не может быть активным",
                () -> status != null && (isPosted() || status == SubscriptionStatus.DRAFT || status == SubscriptionStatus.CANCELLED)));
    }

    @Override
    public void handlePosting(PostingContext context) {
        if (status == SubscriptionStatus.CANCELLED) throw new ValidationException("Подписка отменена");
        context.movements(AccountBalance.class).addExpense(row -> {
            row.setClient(client);
            row.setAmount(total);
        });
        for (SubscriptionLine line : lines) context.movements(TariffRevenue.class).addReceipt(row -> {
            row.setClient(client);
            row.setTariff(line.getTariff());
            row.setAmount(line.getAmount());
            row.setPeriods(BigDecimal.valueOf(line.getPeriods()));
        });
    }
}
