package org.example.subscriptions;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;

/** Test-only JDBC observation/fault points, within the real Jdbi transaction. */
class DocumentFaults implements SqlLogger {
    volatile boolean failSecondLine, failRevenue, failStatus, observedFirstMovement, observedUnpost;
    volatile CyclicBarrier balanceBarrier;
    volatile UUID target;
    String rows, revenue, cash, header;
    AtomicInteger lineCalls = new AtomicInteger();
    AtomicInteger barrierArrivals = new AtomicInteger();

    void clear() {
        failSecondLine = false;
        failRevenue = false;
        failStatus = false;
        observedFirstMovement = false;
        observedUnpost = false;
        balanceBarrier = null;
        target = null;
        lineCalls.set(0);
        barrierArrivals.set(0);
    }

    @Override
    public void logBeforeExecution(StatementContext context) {
        String sql = context.getRawSql();
        if (failSecondLine && sql.startsWith("INSERT INTO " + rows) && lineCalls.incrementAndGet() == 2) throw new IllegalStateException("test late row failure");
        if (failRevenue && sql.startsWith("INSERT INTO " + revenue)) {
            try (var stmt = context.getConnection().createStatement(); var result = stmt.executeQuery("SELECT COUNT(*) FROM " + cash + " WHERE _document_ref='" + target + "' AND _active=TRUE")) {
                result.next();
                observedFirstMovement = result.getLong(1) > 0;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("test second register failure");
        }
        if (failStatus && sql.startsWith("UPDATE " + header + " SET status")) {
            try (var stmt = context.getConnection().createStatement(); var result = stmt.executeQuery("SELECT _posted FROM " + header + " WHERE _id='" + target + "'")) {
                result.next();
                observedUnpost = !result.getBoolean(1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("test CANCELLED save failure");
        }
        CyclicBarrier barrier = balanceBarrier;
        if (barrier != null && sql.startsWith("MERGE INTO " + cash + "_totals")) {
            barrierArrivals.incrementAndGet();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
