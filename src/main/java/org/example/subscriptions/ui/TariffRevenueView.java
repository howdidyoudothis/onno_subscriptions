package org.example.subscriptions.ui;

import org.example.subscriptions.model.TariffRevenue;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class TariffRevenueView implements EntityView<TariffRevenue> {
    @Override
    public Class<TariffRevenue> entity() {
        return TariffRevenue.class;
    }

    @Override
    public void list(ListSpec<TariffRevenue> list) {
        list.columns(TariffRevenue::getTariff, TariffRevenue::getClient, TariffRevenue::getAmount, TariffRevenue::getPeriods)
                .label(TariffRevenue::getTariff, "Тариф").label(TariffRevenue::getClient, "Клиент").label(TariffRevenue::getAmount,
                "Сумма").label(TariffRevenue::getPeriods, "Периодов");
    }

    @Override
    public void fields(EntityConfigBuilder<TariffRevenue> fields) {
        fields.field(TariffRevenue::getPeriod).label("Период").format("dd.MM.yyyy HH:mm:ss");
        fields.refField(TariffRevenue::getTariff).label("Тариф");
        fields.refField(TariffRevenue::getClient).label("Клиент");
        fields.field(TariffRevenue::getAmount).label("Сумма").format("decimal");
        fields.field(TariffRevenue::getPeriods).label("Периодов").format("integer");
    }
}
