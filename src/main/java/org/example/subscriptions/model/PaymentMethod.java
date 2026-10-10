package org.example.subscriptions.model;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "PaymentMethods", title = "Способы оплаты")
public enum PaymentMethod {
    @EnumLabel("Банковская карта")
    CARD,
    @EnumLabel("Банковский перевод")
    BANK_TRANSFER,
    @EnumLabel("Наличные")
    CASH
}
