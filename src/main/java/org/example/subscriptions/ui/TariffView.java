package org.example.subscriptions.ui;

import org.example.subscriptions.model.Tariff;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class TariffView implements EntityView<Tariff> {
    @Override
    public Class<Tariff> entity() {
        return Tariff.class;
    }

    @Override
    public void list(ListSpec<Tariff> list) {
        list.columns(Tariff::getDescription, Tariff::getPrice, Tariff::getPeriodDays, Tariff::isAvailable).label(Tariff::getDescription,
                "Тариф").label(Tariff::getPrice, "Цена за период").label(Tariff::getPeriodDays,
                "Период, дней").label(Tariff::isAvailable, "Доступен для подключения").sortBy(Tariff::getDescription);
    }

    @Override
    public void fields(EntityConfigBuilder<Tariff> fields) {
        fields.field(Tariff::getCode).label("Код").order(0).width("half").hint("Код назначается автоматически");
        fields.field(Tariff::getDescription).label("Тариф").order(10).width("full").hint("Название тарифного плана");
        fields.field(Tariff::getPrice).order(20).width("half").format("decimal").hint("Цена за один период");
        fields.field(Tariff::getPeriodDays).order(30).width("half").format("integer").hint("Длительность одного периода в днях");
        fields.field(Tariff::isAvailable).order(40).width("half");
    }
}
