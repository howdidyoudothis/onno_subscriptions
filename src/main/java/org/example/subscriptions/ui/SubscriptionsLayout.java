package org.example.subscriptions.ui;

import org.example.subscriptions.model.AccountBalance;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.Payment;
import org.example.subscriptions.model.Subscription;
import org.example.subscriptions.model.Tariff;
import org.example.subscriptions.model.TariffRevenue;
import org.springframework.stereotype.Component;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

@Component
public class SubscriptionsLayout implements Layout {
    @Override
    public void configure(LayoutSpec layout) {
        layout.shell().nav(NavStyle.SIDEBAR).brand("Подписки");
        layout.section("Справочники").icon("book-open").catalog(Client.class).catalog(Tariff.class);
        layout.section("Документы").icon("file-text").document(Payment.class).document(Subscription.class);
        layout.section("Учёт").icon("chart-column").register(AccountBalance.class).register(TariffRevenue.class).page("/revenue-report",
                "Отчёт по выручке", "chart-column");
    }
}
