package org.example.subscriptions.model;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "ClientStatuses", title = "Статусы клиента")
public enum ClientStatus {
    @EnumLabel("Активен")
    ACTIVE,
    @EnumLabel("Неактивен")
    INACTIVE
}
