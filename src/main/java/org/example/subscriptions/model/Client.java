package org.example.subscriptions.model;

import java.time.LocalDate;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

@Catalog(name = "Clients", title = "Клиенты", codePrefix = "CL-")
@AccessControl(readRoles = {
    "ADMIN"
}, writeRoles = {
    "ADMIN"
})
public class Client extends CatalogObject {
    @Attribute(displayName = "Статус")
    private ClientStatus status;
    @Attribute(displayName = "E-mail", length = 200, email = true)
    private String email;
    @Attribute(displayName = "Телефон", length = 50)
    private String phone;
    @Attribute(displayName = "Дата регистрации")
    private LocalDate registrationDate;

    public ClientStatus getStatus() {
        return status;
    }

    public void setStatus(ClientStatus status) {
        this.status = status;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getRegistrationDate() {
        return registrationDate;
    }

    public void setRegistrationDate(LocalDate registrationDate) {
        this.registrationDate = registrationDate;
    }
}
