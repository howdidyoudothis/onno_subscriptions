package org.example.subscriptions.ui;

import org.example.subscriptions.model.Payment;
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
public class PaymentView implements EntityView<Payment> {
    private final DocumentOperations operations;

    public PaymentView(DocumentOperations operations) {
        this.operations = operations;
    }

    @Override
    public Class<Payment> entity() {
        return Payment.class;
    }

    @Override
    public void list(ListSpec<Payment> list) {
        list.columns(Payment::getNumber, Payment::getDate, Payment::getClient, Payment::getAmount, Payment::getMethod,
                Payment::isPosted).label(Payment::getNumber, "Номер").label(Payment::getDate, "Дата").label(Payment::isPosted,
                "Проведён");
    }

    @Override
    public void fields(EntityConfigBuilder<Payment> fields) {
        fields.field(Payment::getNumber).label("Номер").order(0).width("half");
        fields.field(Payment::getDate).label("Дата").order(10).width("half").format("dd.MM.yyyy HH:mm");
        fields.refField(Payment::getClient).order(20).width("full");
        fields.field(Payment::getAmount).order(30).width("half").format("decimal").hint("Положительная сумма до копейки. Пополнение произойдёт только при отдельном проведении");
        fields.field(Payment::getMethod).order(40).width("half");
        fields.field(Payment::isPosted).label("Проведён").hideInForm();
        fields.action("post").hidden().action("unpost").inMenu().action("delete").inMenu();
    }

    @Override
    public void actions(ActionSpec actions) {
        actions.action("postSaved").label("Провести сохранённый документ").icon("check").scope(ActionScope.DETAIL).handler(context -> {
            operations.postSaved(Payment.class, context.id(), context.subject());
            return ActionResult.refresh(ActionToast.success("Платёж проведён"));
        });
        actions.action("unpostSaved").label("Снять проведение").icon("undo").scope(ActionScope.DETAIL).handler(context -> {
            operations.unpostSaved(Payment.class, context.id(), context.subject());
            return ActionResult.refresh(ActionToast.success("Проведение снято"));
        });
    }
}
