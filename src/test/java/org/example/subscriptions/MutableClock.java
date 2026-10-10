package org.example.subscriptions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

class MutableClock extends Clock {
    private final AtomicReference<Instant> time = new AtomicReference<>();
    void day(String value) {
        time.set(LocalDate.parse(value).atTime(12, 0).atZone(getZone()).toInstant());
    }

    public ZoneId getZone() {
        return ZoneId.of("Europe/Moscow");
    }

    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
        return time.get();
    }
}
