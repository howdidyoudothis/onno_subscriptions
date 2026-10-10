package org.example.subscriptions.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.example.subscriptions.model.Subscription;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.Repository;

public interface SubscriptionReader extends Repository<Subscription, UUID> {
    List<Subscription> findByPostedTrueAndDeletionMarkFalse();
    @Lock(LockMode.PESSIMISTIC_WRITE)
    Optional<Subscription> findLockedById(UUID id);
    Optional<Subscription> findById(UUID id);
}
