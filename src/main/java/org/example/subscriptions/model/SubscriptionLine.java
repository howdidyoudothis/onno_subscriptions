package org.example.subscriptions.model;

import java.math.BigDecimal;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

public class SubscriptionLine extends TabularSectionRow {
    // Stock UI omits system row ids; this persisted key identifies trusted snapshots across saves.
    @Attribute(displayName = "Ключ снимка", length = 36)
    private String snapshotKey;
    @Attribute(displayName = "Тариф")
    private Ref<Tariff> tariff;
    @Attribute(displayName = "Периодов")
    private Integer periods;
    @Attribute(displayName = "Цена", precision = 15, scale = 2)
    private BigDecimal price;
    @Attribute(displayName = "Сумма", precision = 15, scale = 2)
    private BigDecimal amount;
    @Attribute(displayName = "Дней в периоде (снимок)")
    private Integer snapshotPeriodDays;

    public String getSnapshotKey() {
        return snapshotKey;
    }

    public void setSnapshotKey(String key) {
        this.snapshotKey = key;
    }

    public Ref<Tariff> getTariff() {
        return tariff;
    }

    public void setTariff(Ref<Tariff> tariff) {
        this.tariff = tariff;
    }

    public Integer getPeriods() {
        return periods;
    }

    public void setPeriods(Integer periods) {
        this.periods = periods;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getSnapshotPeriodDays() {
        return snapshotPeriodDays;
    }

    public void setSnapshotPeriodDays(Integer days) {
        this.snapshotPeriodDays = days;
    }
}
