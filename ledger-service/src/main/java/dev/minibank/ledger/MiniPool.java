package dev.minibank.ledger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STAGE 4 — A CONNECTION POOL, WRITTEN BY HAND.
 *
 * Why pools exist: opening a Postgres connection is EXPENSIVE — a TCP
 * handshake, authentication, and a whole server process allocated on the
 * other side. Milliseconds each, times every query, forever. Meanwhile a
 * typical indexed query takes microseconds. Connection-per-query means
 * paying a 100x overhead on everything (the load test proves it).
 *
 * The pool: open N real connections ONCE, keep them in a queue, lend them
 * out, take them back. That's the whole idea. HikariCP and PgBouncer are
 * this, plus a decade of hardening (health checks, leak detection, metrics).
 *
 * THE INDUSTRY'S CLEVEREST TRICK, reproduced here: borrowers receive a
 * PROXY whose close() does not close anything — it RETURNS the real
 * connection to the pool. That is why all our existing code (Ledger,
 * HttpApi) works unchanged with try-with-resources: "closing" a pooled
 * connection has always secretly meant "give it back".
 *
 * THE HYGIENE RULE: connections carry state. A borrower that set
 * autoCommit(false) and crashed would poison the next borrower with an
 * open transaction — so on every return we rollback if needed and reset
 * autoCommit(true). State never leaks between borrowers.
 */
public final class MiniPool implements AutoCloseable {

    private final ArrayBlockingQueue<Connection> idle;
    private final AtomicInteger borrowed = new AtomicInteger();
    private final int size;

    public MiniPool(String url, String user, String password, int size) throws SQLException {
        this.size = size;
        this.idle = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            idle.add(DriverManager.getConnection(url, user, password));
        }
    }

    /** Borrow a connection; blocks (bounded) if the pool is exhausted —
     *  that blocking IS backpressure: better to queue briefly at the pool
     *  than to melt the database with unbounded connections. */
    public Connection borrow(long timeout, TimeUnit unit) throws SQLException {
        try {
            Connection real = idle.poll(timeout, unit);
            if (real == null) throw new SQLException("pool exhausted: all " + size + " connections busy");
            borrowed.incrementAndGet();
            return wrap(real);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a connection");
        }
    }

    private void giveBack(Connection real) {
        try {
            if (!real.getAutoCommit()) {   // borrower left a transaction open:
                real.rollback();           // undo whatever half-work remains,
                real.setAutoCommit(true);  // and reset state for the next borrower
            }
        } catch (SQLException e) {
            // a connection we can't clean is a connection we don't reuse
            try { real.close(); } catch (SQLException ignored) {}
            borrowed.decrementAndGet();
            return;
        }
        borrowed.decrementAndGet();
        idle.offer(real);
    }

    /** The proxy: every method passes through to the real connection,
     *  except close(), which returns it to the pool instead. */
    private Connection wrap(Connection real) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean released = false;
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                if ("close".equals(m.getName())) {
                    if (!released) { released = true; giveBack(real); }
                    return null;
                }
                if ("isClosed".equals(m.getName()) && released) return true;
                if (released) throw new SQLException("connection already returned to pool");
                return m.invoke(real, args);
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    public int idleCount() { return idle.size(); }
    public int borrowedCount() { return borrowed.get(); }
    public int size() { return size; }

    @Override
    public void close() {
        Connection c;
        while ((c = idle.poll()) != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }
}
