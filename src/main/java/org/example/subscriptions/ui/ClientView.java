package org.example.subscriptions.ui;

import org.example.subscriptions.model.Client;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class ClientView implements EntityView<Client> {
    @Override
    public Class<Client> entity() {
        return Client.class;
    }

    @Override
    public void list(ListSpec<Client> list) {
        list.columns(Client::getDescription, Client::getStatus, Client::getEmail,
                Client::getPhone, Client::getRegistrationDate).label(Client::getDescription, "Клиент").label(Client::getStatus,
                "Статус").label(Client::getEmail, "E-mail").label(Client::getPhone, "Телефон").label(Client::getRegistrationDate,
                "Дата регистрации").sortBy(Client::getDescription);
    }

    @Override
    public void fields(EntityConfigBuilder<Client> fields) {
        fields.field(Client::getCode).label("Код").order(0).width("half").hint("Код назначается автоматически");
        fields.field(Client::getDescription).label("Клиент").order(10).width("full").hint("Имя или название клиента");
        fields.field(Client::getStatus).order(20).width("half");
        fields.field(Client::getEmail).order(30).width("half").placeholder("client@example.com");
        fields.field(Client::getPhone).order(40).width("half").hint("Контактный телефон");
        fields.field(Client::getRegistrationDate).order(50).width("half").format("dd.MM.yyyy");
    }
}
