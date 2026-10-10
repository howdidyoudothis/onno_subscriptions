package org.example.subscriptions.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.TariffRevenue;
import org.springframework.stereotype.Component;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.ActionContext;
import su.onno.ui.ActionDialog;
import su.onno.ui.ActionRejectedException;
import su.onno.ui.ActionResult;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.InputType;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;
import su.onno.ui.RegisterQueryService;

/** Read-only report: each invocation owns its parameters and uses the caller's subject. */
@Component
public class RevenueReportPage implements Page {
    private final RegisterQueryService registers;
    private final CatalogQueryService catalogs;
    private final MetadataRegistry metadata;

    public RevenueReportPage(RegisterQueryService registers, CatalogQueryService catalogs, MetadataRegistry metadata) {
        this.registers = registers;
        this.catalogs = catalogs;
        this.metadata = metadata;
    }

    @Override
    public String route() {
        return "/revenue-report";
    }

    @Override
    public void compose(PageBuilder page) {
        page.title("Отчёт по выручке");
        page.text("Период — даты финансовых движений включительно, а не срок подписки. Отчёт не изменяет учётные данные.");
        page.actions("Отчёт", actions -> actions.action("generate").label("Сформировать").roles("ADMIN").form(form -> {
            form.title("Параметры отчёта").submitLabel("Сформировать");
            form.input("from").label("Дата начала").type(InputType.DATE).required();
            form.input("to").label("Дата окончания").type(InputType.DATE).required();
            form.input("client").label("Клиент (необязательно)").reference(Client.class);
        }).handler(this::generate));
    }

    private ActionResult generate(ActionContext ctx) {
        LocalDate from;
        LocalDate to;
        UUID client = null;
        try {
            from = LocalDate.parse(ctx.input("from"));
            to = LocalDate.parse(ctx.input("to"));
            if (!ctx.input("client").isBlank()) client = UUID.fromString(ctx.input("client"));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw ActionRejectedException.message("Укажите корректные даты и клиента");
        }
        if (from.isAfter(to)) {
            throw ActionRejectedException.builder().message("Дата начала не должна быть позже даты окончания").fieldError("to",
                    "Дата окончания должна быть не раньше даты начала").build();
        }
        String clientLabel = "Все клиенты";
        if (client != null) {
            clientLabel = String.valueOf(catalogs.get(ctx.subject(), metadata.getCatalogDescriptor(Client.class),
                    client).get("_description"));
        }
        // Stock _period is TIMESTAMP(6): preserve the last microsecond without rounding into the next day.
        var rows = registers.turnover(ctx.subject(), metadata.getRegisterDescriptor(TariffRevenue.class),
                from.atStartOfDay().toString(), to.atTime(LocalTime.of(23, 59, 59, 999_999_000)).toString(),
                client == null ? Map.of() : Map.of("client", client.toString()));
        var dates = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        var dialog = ActionDialog.info("Отчёт по выручке").message("Период: " + from.format(dates) + " — " + to.format(dates) + ". " + clientLabel)
                .dismissLabel("Закрыть");
        if (rows.isEmpty()) dialog.detail("Нет выручки за выбранный период");
        for (var row : rows) {
            dialog.detail("Клиент: " + row.get("client_display") + " · Тариф: " + row.get("tariff_display")
                    + " · Сумма: " + new BigDecimal(row.get("amount").toString()).setScale(2).toPlainString()
                    + " · Периоды: " + new BigDecimal(row.get("periods").toString()).intValueExact());
        }
        return ActionResult.dialog(dialog);
    }
}
