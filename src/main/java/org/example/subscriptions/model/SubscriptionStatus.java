package org.example.subscriptions.model;

import java.time.LocalDate;
import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "SubscriptionStatuses", title = "Статусы подписки")
public enum SubscriptionStatus {
    @EnumLabel("Черновик")
    DRAFT,
    @EnumLabel("Ожидает начала")
    WAITING,
    @EnumLabel("Активна")
    ACTIVE,
    @EnumLabel("Истекла")
    EXPIRED,
    @EnumLabel("Отменена")
    CANCELLED;

    public static SubscriptionStatus posted(LocalDate start, LocalDate end, LocalDate today) {
        return today.isBefore(start) ? WAITING : today.isBefore(end) ? ACTIVE : EXPIRED;
    }
}
