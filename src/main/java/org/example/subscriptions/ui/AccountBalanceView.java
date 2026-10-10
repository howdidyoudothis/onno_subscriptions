package org.example.subscriptions.ui;

import org.example.subscriptions.model.AccountBalance;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class AccountBalanceView implements EntityView<AccountBalance> {
    @Override
    public Class<AccountBalance> entity() {
        return AccountBalance.class;
    }

    @Override
    public void list(ListSpec<AccountBalance> list) {
        list.columns(AccountBalance::getClient, AccountBalance::getAmount).label(AccountBalance::getClient,
                "Клиент").label(AccountBalance::getAmount, "Остаток");
    }

    @Override
    public void fields(EntityConfigBuilder<AccountBalance> fields) {
        fields.refField(AccountBalance::getClient).label("Клиент");
        fields.field(AccountBalance::getAmount).label("Остаток").format("decimal");
        fields.field(AccountBalance::getPeriod).label("Период").format("dd.MM.yyyy HH:mm:ss");
    }
}
