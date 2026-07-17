# minibank

A neobank built from first principles, one lesson at a time. **Raw Java 21 — no frameworks** · PostgreSQL · Kafka · Kubernetes.

> Learning project: each stage builds one real piece of a modern fintech backend and **proves one system-design concept with a runnable demo**. Inspired by how contemporary neobanks are engineered (microservices, database-per-service, event-driven consistency, and in-house tooling over frameworks). Not affiliated with any bank.

## The destination

A public site with two faces:

1. **The app** — a working neobank: sign in, see balances, send money, watch notifications arrive. Correct under concurrency, because every mechanism underneath was proven in a test first.
2. **The X-ray tab** — a visual explorer of the database itself: accounts, ledger entries, the outbox, Kafka consumer positions, per-shard row counts. Every lesson in this repo becomes something you can *see happen*.

## Doctrine: no frameworks

Everything a framework would hand us invisibly, we build visibly, when the lesson calls for it:

| A framework gives you | We instead | Stage |
|---|---|---|
| connection pool (auto) | feel the pain of connection-per-query, then write a tiny pool, then PgBouncer | 0 → 4 |
| transaction management | explicit `setAutoCommit(false)` … `commit()` / `rollback()` | 0 |
| schema init | we run our own DDL | 0 |
| HTTP endpoints | JDK's built-in `HttpServer` + virtual threads | 2 |
| Kafka magic | raw producer/consumer + a hand-written transactional outbox | 2 |

Dependencies so far: the Postgres driver and JUnit. That's it.

## Decision log

Every design decision is recorded and argued — the point of this repo is understanding, not shipping.

- **D1** Raw JDBC via `DriverManager`, one connection per call — *deliberately naive*; stage 4 measures the cost and fixes it.
- **D2** Each racing test thread gets its own connection — sharing one connection would queue, not race; real systems race because every request has its own connection.
- **D3** Stage 0 stores balance as a mutable column — *deliberately wrong*; stage 1 replaces it with an append-only ledger and shows why.
- **D4** (Igor) Ledger schema: double-entry with a cached balance, built as pure double-entry first, cache derived after — chosen to maximise interview-relevant depth: the sum-to-zero invariant, truth-vs-projection, and reconciliation.
- **D5** Money only enters the bank by transfer: accounts are born empty and the external *world* account funds them (going negative — its job). Keeps the invariant pure: cache == SUM(entries), always, for everyone.
- **D6** Business rules live in the schema: the balance check is kind-aware — customers never negative, externals unbounded.
- **D7** Concurrency correctness belongs to the database, not the JVM: ordered FOR UPDATE locking (ascending account id) makes deadlock impossible; the caller-supplied transaction id doubles as the idempotency key via the primary key.

## The curriculum

| Stage | Build | The lesson it proves |
|---|---|---|
| **0** | `ledger-service`: accounts table + four concurrency tests | The lost update, watched live — then killed 3 ways: `FOR UPDATE`, version column, atomic update |
| **1** | Double-entry ledger: append-only entries, balance as projection | Why banks ledger instead of update |
| **2** | `cards-service` + Kafka + transactional outbox | Consistency *between* services: events, idempotency, eventual |
| **3** | docker-compose all services + gateway + minimal UI | Microservices, database-per-service |
| **4** | PgBouncer + read replica + load test (with/without pooling) | A database drowns in connections before it drowns in data |
| **5** | Shard the ledger by customer_id (2 shards + router); cross-shard transfer saga | Clones scale by adding more; territories scale by redrawing the map |
| **6** | Two regions (eu/uk), residency routing, K8s manifests | The 75-million-customer answer, end to end |

## The four-line architecture (memorize this)

```
App tier        → stateless pods on K8s, scale freely
Service split   → microservices, one PostgreSQL per service
Hot service DB  → one strong PG + pooling → read replicas → shard by customer_id
Between services→ transactional outbox → Kafka → idempotent consumers
```

ACID lives inside one service's database. Between services there are only events.
Money is strict now; echoes arrive milliseconds later.

## Status

- [x] Stage 0 — the lost update, killed three ways
- [x] Stage 1 — the double-entry ledger (deadlock provoked and cured, idempotent retries, reconciliation)
- [ ] Stage 2–6

## Run

```bash
docker compose up -d postgres
cd ledger-service && mvn test   # the concurrency lessons live in the tests
```
