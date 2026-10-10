package org.example.subscriptions.ui;

import org.example.subscriptions.model.Subscription;
import org.example.subscriptions.model.SubscriptionLine;
import org.example.subscriptions.model.Tariff;
import org.example.subscriptions.service.DocumentOperations;
import org.springframework.stereotype.Component;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.ActionToast;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class SubscriptionView implements EntityView<Subscription> {
    private final DocumentOperations operations;

    public SubscriptionView(DocumentOperations operations) {
        this.operations = operations;
    }

    @Override
    public Class<Subscription> entity() {
        return Subscription.class;
    }

    @Override
    public void list(ListSpec<Subscription> list) {
        list.columns(Subscription::getNumber, Subscription::getClient, Subscription::getStatus,
                Subscription::getStart, Subscription::getEndDate, Subscription::getTotal, Subscription::isPosted).label(Subscription::getNumber,
                "Номер").label(Subscription::isPosted, "Проведена");
    }

    @Override
    public void fields(EntityConfigBuilder<Subscription> fields) {
        fields.field(Subscription::getNumber).label("Номер").order(0).width("half");
        fields.field(Subscription::getDate).label("Дата документа").order(10).width("half").format("dd.MM.yyyy HH:mm");
        fields.refField(Subscription::getClient).order(20).width("full");
        fields.field(Subscription::getStatus).order(30).hideInForm().hint("Статус определяется проведением, снятием проведения и отменой");
        fields.field(Subscription::getStart).order(40).width("half").format("dd.MM.yyyy");
        fields.field(Subscription::getEndDate).order(50).width("half").format("dd.MM.yyyy").hint("Рассчитывается после сохранения. День окончания не входит в срок действия");
        fields.field(Subscription::getTotal).order(60).format("decimal").hint("Сначала сохраните черновик: цена, суммы и срок рассчитываются автоматически. Затем проверьте результат и отдельно проведите. Черновик не списывает деньги");
        fields.field(Subscription::isPosted).label("Проведена").hideInForm();
        fields.rowRefField(Subscription::getLines, SubscriptionLine::getTariff).label("Тариф").order(0).refSecondary(Tariff::getPrice);
        fields.rowField(Subscription::getLines, SubscriptionLine::getPeriods).label("Периодов").order(10).format("integer");
        fields.rowField(Subscription::getLines, SubscriptionLine::getPrice).label("Цена").order(20).format("decimal").hint("Берётся из тарифа при сохранении, ручная цена не применяется");
        fields.rowField(Subscription::getLines, SubscriptionLine::getAmount).label("Сумма").order(30).format("decimal");
        fields.rowField(Subscription::getLines, SubscriptionLine::getSnapshotPeriodDays).label("Дней в периоде").order(40)
                .format("integer");
        fields.rowField(Subscription::getLines, SubscriptionLine::getSnapshotKey).hideInForm().hideInList().hideInDetail();
        fields.action("post").hidden().action("unpost").inMenu().action("delete").inMenu();
    }

    @Override
    public void actions(ActionSpec actions) {
        actions.action("postSaved").label("Провести сохранённый документ").icon("check").scope(ActionScope.DETAIL).handler(context -> {
            operations.postSaved(Subscription.class, context.id(), context.subject());
            return ActionResult.refresh(ActionToast.success("Подписка проведена"));
        });
        actions.action("unpostSaved").label("Снять проведение").icon("undo").scope(ActionScope.DETAIL).handler(context -> {
            operations.unpostSaved(Subscription.class, context.id(), context.subject());
            return ActionResult.refresh(ActionToast.success("Проведение снято"));
        });
        actions.action("cancel").label("Отменить подписку").icon("ban").scope(ActionScope.DETAIL).handler(context -> {
            operations.cancel(context.id(), context.subject());
            return ActionResult.refresh(ActionToast.success("Подписка отменена"));
        });
    }
}
