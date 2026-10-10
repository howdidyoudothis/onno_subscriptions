package org.example.subscriptions.model;

import java.math.BigDecimal;
import java.util.List;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

@Catalog(name = "Tariffs", title = "Тарифы", codePrefix = "TR-")
@AccessControl(readRoles = {
    "ADMIN"
}, writeRoles = {
    "ADMIN"
})
public class Tariff extends CatalogObject implements Validated {
    @Attribute(displayName = "Цена за период", precision = 15, scale = 2)
    private BigDecimal price;
    @Attribute(displayName = "Период, дней")
    private Integer periodDays;
    @Attribute(displayName = "Доступен для подключения")
    private boolean available;

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getPeriodDays() {
        return periodDays;
    }

    public void setPeriodDays(Integer periodDays) {
        this.periodDays = periodDays;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(BusinessRule.onField("price", "Цена должна быть положительной, с точностью до копейки",
                () -> Money.valid(price)),
                BusinessRule.onField("periodDays", "Длительность периода должна быть положительной", () -> periodDays != null && periodDays > 0));
    }
}
