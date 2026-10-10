package org.example.subscriptions.model;

import java.math.BigDecimal;
import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

@AccumulationRegister(name = "TariffRevenues", title = "Выручка по тарифам", type = AccumulationType.TURNOVER)
@AccessControl(readRoles = {
    "ADMIN"
}, writeRoles = {
    "ADMIN"
})
public class TariffRevenue extends AccumulationRecord {
    @Dimension(displayName = "Тариф")
    private Ref<Tariff> tariff;
    @Dimension(displayName = "Клиент")
    private Ref<Client> client;
    @Resource(displayName = "Сумма", precision = 15, scale = 2)
    private BigDecimal amount;
    @Resource(displayName = "Периодов", precision = 15, scale = 0)
    private BigDecimal periods;

    public Ref<Tariff> getTariff() {
        return tariff;
    }

    public void setTariff(Ref<Tariff> tariff) {
        this.tariff = tariff;
    }

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

    public BigDecimal getPeriods() {
        return periods;
    }

    public void setPeriods(BigDecimal periods) {
        this.periods = periods;
    }
}
