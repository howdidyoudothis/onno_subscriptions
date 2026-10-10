package org.example.subscriptions.service;

import java.util.Optional;
import java.util.UUID;
import org.example.subscriptions.model.Payment;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.Repository;

/** Read-only repository surface: writes must use the guarded document commands. */
public interface PaymentReader extends Repository<Payment, UUID> {
    @Lock(LockMode.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedById(UUID id);
    Optional<Payment> findById(UUID id);
}
