package org.example.subscriptions.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.Money;
import org.example.subscriptions.model.Payment;
import org.example.subscriptions.model.PaymentMethod;
import org.example.subscriptions.model.Subscription;
import org.example.subscriptions.model.SubscriptionStatus;
import org.example.subscriptions.model.Tariff;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.rules.BusinessRule;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiProperties;
import su.onno.validation.ValidationException;

/** The two financial aggregates share the stock commands and the same DataSource transaction. */
@Service
public class DocumentOperations {
    private final TransactionTemplate transaction;
    private final PaymentReader payments;
    private final SubscriptionReader subscriptions;
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final MetadataRegistry metadata;
    private final UiAccessService access;
    private final UiProperties properties;
    private final Clock clock;
    private final ObjectProvider<DocumentCommandService> commands;

    public DocumentOperations(PlatformTransactionManager manager, PaymentReader payments, SubscriptionReader subscriptions,
            CatalogQueryService catalogs, DocumentQueryService documents, MetadataRegistry metadata,
            UiAccessService access, UiProperties properties, Clock clock, ObjectProvider<DocumentCommandService> commands) {
        this.transaction = new TransactionTemplate(manager);
        this.payments = payments;
        this.subscriptions = subscriptions;
        this.catalogs = catalogs;
        this.documents = documents;
        this.metadata = metadata;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
        this.commands = commands;
    }

    public void cancel(UUID id, AccessSubject subject) {
        commands.getObject().update(metadata.getDocumentDescriptor(Subscription.class), id,
                Map.of("status", enumId(SubscriptionStatus.CANCELLED)), Objects.requireNonNull(subject));
    }

    /** Trusted worker path: re-read under the same lock, then a stock status-only lifecycle write. */
    public boolean refreshStatus(UUID id, LocalDate today) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            var current = subscriptions.findLockedById(id);
            if (current.isEmpty()) return false;
            var document = current.get();
            if (!document.isPosted() || document.isDeletionMark() || document.getStatus() == SubscriptionStatus.CANCELLED) return false;
            var desired = SubscriptionStatus.posted(document.getStart(), document.getEndDate(), today);
            if (document.getStatus() == desired) return false;
            // This is our own single class proxy. Bypass only the external financial-edit guard;
            // stock authorization, lifecycle and rules still run, with no submitted lines.
            Object target = AopProxyUtils.getSingletonTarget(commands.getObject());
            if (!(target instanceof DocumentCommandService stock)) throw new IllegalStateException("Guarded document command target is unavailable");
            saveStatus(stock, metadata.getDocumentDescriptor(Subscription.class), id, AccessSubject.system(),
                    desired);
            return true;
        }));
    }

    public void postSaved(Class<?> type, UUID id, AccessSubject subject) {
        if (type != Payment.class && type != Subscription.class) throw new IllegalArgumentException("Unsupported document");
        commands.getObject().post(metadata.getDocumentDescriptor(type), id, Objects.requireNonNull(subject));
    }

    public void unpostSaved(Class<?> type, UUID id, AccessSubject subject) {
        if (type != Payment.class && type != Subscription.class) throw new IllegalArgumentException("Unsupported document");
        commands.getObject().unpost(metadata.getDocumentDescriptor(type), id, Objects.requireNonNull(subject));
    }

    public void checkTariffDays(CatalogDescriptor descriptor, Object days, AccessSubject subject, String method) {
        access.requireWrite(subject, descriptor);
        if (!method.equals("validate") && properties.isReadOnly()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "UI is in read-only mode");
        if (days != null) Money.positiveInteger(days);
    }

    public static UUID enumId(Enum<?> value) {
        return UUID.nameUUIDFromBytes((value.getDeclaringClass().getName() + "." + value.name()).getBytes(StandardCharsets.UTF_8));
    }

    public Object intercept(DocumentCommandService target, MethodInvocation call) throws Throwable {
        Object[] args = call.getArguments();
        if (args.length == 0 || !(args[0] instanceof DocumentDescriptor desc)
                || (desc.javaClass() != Payment.class && desc.javaClass() != Subscription.class)) return call.proceed();
        String method = call.getMethod().getName();
        if (!Set.of("create", "update", "validate", "post", "unpost", "delete", "postingPreview").contains(method)) return call.proceed();
        return transaction.execute(status -> {
            try {
                return execute(target, call, desc, method);
            } catch (IllegalStateException failure) {
                // 3.4.1 exposes this guard as IllegalStateException, with no typed exception.
                if ("Insufficient сумма in register \"AccountBalances\". Posting would result in negative balance.".equals(failure.getMessage()))
                    throw new ValidationException("Недостаточно средств на лицевом счёте для этой операции");
                throw failure;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Object execute(DocumentCommandService target, MethodInvocation call, DocumentDescriptor desc,
            String method) throws Throwable {
        Object[] args = call.getArguments();
        AccessSubject subject = (AccessSubject) args[args.length - 1];
        boolean preview = method.equals("postingPreview");
        if (preview) access.requireRead(subject, desc);
        else access.requireWrite(subject, desc);
        if (!preview && !method.equals("validate") && properties.isReadOnly()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "UI is in read-only mode");
        UUID id = method.equals("create") ? null : (UUID) args[1];
        Map<String, Object> stored = null;
        if (id != null) {
            if (!preview && !target.isWritable(desc, id, subject)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            // Acquire the DB row lock BEFORE any stored aggregate is read or any early return.
            if (desc.javaClass() == Payment.class) payments.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            else subscriptions.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            stored = documents.get(subject, desc, id);
            if (Boolean.TRUE.equals(stored.get("_deletion_mark"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Документ удалён");
        }
        if (Set.of("create", "update", "validate").contains(method)) {
            int bodyIndex = method.equals("create") ? 1 : 2;
            Map<String, Object> body = normalize(desc, (Map<String, Object>) args[bodyIndex]);
            try {
                rejectSystemFields(body);
                if (stored != null && body.containsKey("version") && !String.valueOf(body.get("version")).equals(String.valueOf(stored.get("_version"))))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Документ изменён, обновите форму");
                if (desc.javaClass() == Subscription.class && enumId(SubscriptionStatus.CANCELLED).toString().equals(String.valueOf(body.get("status")))) {
                    if (!method.equals("update") || !Set.of("status", "version").containsAll(body.keySet())) throw new ValidationException("Отменяйте сохранённую подписку отдельной операцией");
                    return cancelStored(target, desc, id, subject, stored);
                }
                if (stored != null && (Boolean.TRUE.equals(stored.get("_posted")) || cancelled(stored))) throw new ValidationException("Редактирование проведённого или отменённого документа запрещено. Сначала снимите проведение");
                prepare(desc, body, stored, subject);
                args[bodyIndex] = body;
            } catch (ValidationException invalid) {
                if (method.equals("validate")) return Map.of("valid", false, "fieldErrors", Map.of(), "formErrors",
                        List.of(invalid.getMessage()));
                throw invalid;
            }
            return call.proceed();
        }
        if (method.equals("post") || preview) {
            if (desc.javaClass() == Subscription.class && cancelled(stored)) throw new ValidationException("Подписка отменена");
            if (Boolean.TRUE.equals(stored.get("_posted")) && !preview) return stored;
            // no repost; access/scope were checked
            Map<String, Object> verified = normalize(desc, stored);
            verified.remove("status");
            // persisted posted status is not a requested manual transition
            prepare(desc, verified, stored, subject);
            // recheck availability, never silently reprice
            Object result = call.proceed();
            if (!preview && desc.javaClass() == Subscription.class) {
                LocalDate start = LocalDate.parse(stored.get("start").toString());
                LocalDate end = LocalDate.parse(stored.get("end_date").toString());
                return saveStatus(target, desc, id, subject, SubscriptionStatus.posted(start, end, LocalDate.now(clock)));
            }
            return result;
        }
        if (method.equals("unpost")) {
            if (!Boolean.TRUE.equals(stored.get("_posted"))) return stored;
            Object result = call.proceed();
            if (desc.javaClass() == Subscription.class) return saveStatus(target, desc, id, subject, SubscriptionStatus.DRAFT);
            return result;
        }
        // The stock delete calls its PostingService directly: disallow financial deletion.
        if (Boolean.TRUE.equals(stored.get("_posted"))) throw new ValidationException("Сначала снимите проведение документа");
        return call.proceed();
    }

    private Object cancelStored(DocumentCommandService target, DocumentDescriptor desc, UUID id, AccessSubject subject,
            Map<String, Object> stored) {
        if (cancelled(stored)) return stored;
        if (Boolean.TRUE.equals(stored.get("_posted"))) target.unpost(desc, id, subject);
        return saveStatus(target, desc, id, subject, SubscriptionStatus.CANCELLED);
    }

    private Map<String, Object> saveStatus(DocumentCommandService target, DocumentDescriptor desc, UUID id,
            AccessSubject subject, SubscriptionStatus status) {
        // Trusted status-only transition. Target still performs stock authorization, rules and scope.
        return target.update(desc, id, Map.of("status", enumId(status)), subject);
    }

    private boolean cancelled(Map<String, Object> stored) {
        return enumId(SubscriptionStatus.CANCELLED).toString().equals(String.valueOf(stored.get("status")));
    }

    @SuppressWarnings("unchecked")
    private void prepare(DocumentDescriptor desc, Map<String, Object> body, Map<String, Object> stored,
            AccessSubject subject) {
        if (stored == null) {
            if (!body.containsKey("date") || body.get("date") == null || body.get("date").toString().isBlank()) body.put("date",
                    LocalDateTime.now(clock).toString());
        }
        Object client = body.containsKey("client") ? body.get("client") : stored == null ? null : stored.get("client");
        requireCatalog(Client.class, client, subject, false);
        if (desc.javaClass() == Payment.class) {
            if (stored == null && !body.containsKey("method")) body.put("method", enumId(PaymentMethod.CARD));
            return;
        }
        if (body.containsKey("status") && !enumId(SubscriptionStatus.DRAFT).toString().equals(String.valueOf(body.get("status")))) throw new ValidationException("Статус подписки изменяется только проведением или отменой");
        body.put("status", enumId(SubscriptionStatus.DRAFT));
        if (stored == null && (!body.containsKey("start") || body.get("start") == null || body.get("start").toString().isBlank())) body.put("start",
                LocalDate.now(clock).toString());
        body.remove("total");
        body.remove("endDate");
        body.remove("end");
        List<Map<String, Object>> saved = stored == null ? List.of() : (List<Map<String, Object>>) stored.getOrDefault("lines",
                List.of());
        Object raw = body.containsKey("lines") ? body.get("lines") : saved;
        if (!(raw instanceof List<?> rows) || rows.isEmpty()) throw new ValidationException("Добавьте хотя бы одну строку тарифа");
        List<Map<String, Object>> prepared = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof Map<?, ?> source)) throw new ValidationException("Некорректная строка тарифа");
            Map<String, Object> row = new LinkedHashMap<>();
            source.forEach((k, v) -> row.put(k.toString(), v));
            for (var attr : desc.tabularSections().getFirst().attributes()) alias(row, attr.fieldName(), attr.columnName());
            if (row.get("periods") != null) row.put("periods", Money.positiveInteger(row.get("periods")));
            Map<String, Object> tariff = requireCatalog(Tariff.class, row.get("tariff"), subject, true);
            String key = row.get("snapshotKey") == null ? null : row.get("snapshotKey").toString();
            Map<String, Object> previous = null;
            if (stored != null && key != null && !key.isBlank()) {
                previous = saved.stream().filter(old -> key.equals(old.get("snapshot_key"))).findFirst().orElseThrow(() -> new ValidationException("Строка не принадлежит сохранённой подписке. Обновите форму"));
                if (!usedKeys.add(key)) throw new ValidationException("Повтор ключа строки подписки");
            }
            row.put("snapshotKey", previous == null ? UUID.randomUUID().toString() : key);
            if (previous != null && !Objects.equals(String.valueOf(previous.get("tariff")), String.valueOf(row.get("tariff")))) previous = null;
            row.put("price", previous == null ? tariff.get("price") : previous.get("price"));
            row.put("snapshotPeriodDays", previous == null ? tariff.get("period_days") : previous.get("snapshot_period_days"));
            row.remove("amount");
            if (row.get("snapshotPeriodDays") == null && previous == null) row.put("snapshotPeriodDays", tariff.get("periodDays"));
            prepared.add(row);
        }
        body.put("lines", prepared);
    }

    private Map<String, Object> requireCatalog(Class<?> type, Object value, AccessSubject subject,
            boolean available) {
        Map<String, Object> row = null;
        if (value != null && !value.toString().isBlank()) {
            try {
                UUID id = UUID.fromString(value.toString());
                var desc = metadata.getCatalogDescriptor(type);
                access.requireRead(subject, desc);
                row = catalogs.get(subject, desc, id);
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException("Некорректная ссылка на " + type.getSimpleName());
            }
        }
        final Map<String, Object> found = row;
        BusinessRule rule = new BusinessRule("reference", type == Tariff.class ? "Тариф должен существовать и быть доступным" : "Укажите существующего клиента",
                () -> found != null && !Boolean.TRUE.equals(found.get("_deletion_mark")) && (!available || Boolean.TRUE.equals(found.get("available"))));
        if (!rule.holds()) throw new ValidationException(rule.message());
        return row;
    }

    private void rejectSystemFields(Map<String, Object> body) {
        for (String field : List.of("posted", "_posted", "deletionMark", "_deletion_mark", "id", "_id"))
            if (body.containsKey(field)) throw new ValidationException("Системное поле нельзя изменять: " + field);
    }

    private Map<String, Object> normalize(DocumentDescriptor desc, Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>(input);
        alias(body, "number", "_number");
        alias(body, "date", "_date");
        alias(body, "version", "_version");
        for (var attr : desc.attributes()) alias(body, attr.fieldName(), attr.columnName());
        return body;
    }

    private void alias(Map<String, Object> body, String field, String column) {
        if (field.equals(column) || !body.containsKey(column)) return;
        if (body.containsKey(field) && !Objects.equals(body.get(field), body.get(column))) throw new ValidationException("Противоречивые значения поля " + field);
        body.put(field, body.remove(column));
    }
}
