package dev.b4rruf3t.sso;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory fake of the SSO database, for unit tests.
 * Implements just enough JDBC surface for UserDirectory and SessionStore:
 * users and sessions tables, the exact queries those classes run.
 *
 * Not a general SQL engine — a test double that knows the two tables.
 */
final class FakeDb implements ConnectionSource {

    final Map<String, Map<String, Object>> users = new ConcurrentHashMap<>();       // by email
    final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();    // by token hash

    String passwordHashFor(String email) {
        var u = users.get(email);
        return u == null ? null : (String) u.get("password_hash");
    }

    @Override
    public Connection getConnection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> prepare((String) args[0]);
                case "close" -> null;
                case "isClosed" -> false;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private PreparedStatement prepare(String sql) {
        return (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            new FakeStatement(sql));
    }

    /** The invocation handler behind the PreparedStatement proxy. */
    private final class FakeStatement implements java.lang.reflect.InvocationHandler {
        private final String sql;
        private final Map<Integer, Object> params = new HashMap<>();

        FakeStatement(String sql) { this.sql = sql; }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "setString" -> { params.put((Integer) args[0], args[1]); yield null; }
                case "setTimestamp" -> { params.put((Integer) args[0], args[1]); yield null; }
                case "executeUpdate" -> executeUpdate();
                case "executeQuery" -> executeQuery();
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private int executeUpdate() throws SQLException {
            if (sql.startsWith("INSERT INTO users")) {
                String email = (String) params.get(2);
                if (users.containsKey(email)) {
                    // mimic the unique constraint the real schema enforces.
                    // thrown bare (not wrapped) — JDBC proxies pass checked
                    // exceptions through, and UserDirectory catches SQLException.
                    throw new SQLException("unique constraint violation");
                }
                var row = new HashMap<String, Object>();
                row.put("id", params.get(1));
                row.put("email", email);
                row.put("password_hash", params.get(3));
                row.put("display_name", params.get(4));
                row.put("created_at", Timestamp.from(java.time.Instant.now()));
                users.put(email, row);
                return 1;
            }
            if (sql.startsWith("INSERT INTO sessions")) {
                var row = new HashMap<String, Object>();
                row.put("id", params.get(1));
                row.put("user_id", params.get(2));
                row.put("refresh_token_hash", params.get(3));
                row.put("expires_at", params.get(4));
                row.put("revoked_at", null);
                sessions.put((String) params.get(3), row);
                return 1;
            }
            if (sql.startsWith("UPDATE sessions SET revoked_at = NOW() WHERE refresh_token_hash")) {
                var row = sessions.get((String) params.get(1));
                if (row != null) { row.put("revoked_at", Timestamp.from(java.time.Instant.now())); return 1; }
                return 0;
            }
            if (sql.startsWith("UPDATE sessions SET revoked_at = NOW() WHERE user_id")) {
                int n = 0;
                for (var row : sessions.values()) {
                    if (params.get(1).equals(row.get("user_id")) && row.get("revoked_at") == null) {
                        row.put("revoked_at", Timestamp.from(java.time.Instant.now()));
                        n++;
                    }
                }
                return n;
            }
            throw new UnsupportedOperationException("update: " + sql);
        }

        private ResultSet executeQuery() {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (sql.contains("FROM users WHERE email = ?")) {
                var row = users.get((String) params.get(1));
                if (row != null) rows.add(project(row, sql));
            } else if (sql.contains("FROM users WHERE id = ?")) {
                for (var row : users.values()) {
                    if (params.get(1).equals(row.get("id"))) { rows.add(project(row, sql)); break; }
                }
            } else if (sql.contains("FROM sessions WHERE refresh_token_hash = ?")) {
                var row = sessions.get((String) params.get(1));
                if (row != null
                    && row.get("revoked_at") == null
                    && ((Timestamp) row.get("expires_at")).toInstant().isAfter(java.time.Instant.now())) {
                    rows.add(Map.of("user_id", row.get("user_id")));
                }
            } else {
                throw new UnsupportedOperationException("query: " + sql);
            }
            return fakeResultSet(rows);
        }

        /** Return only the columns the SELECT asked for, like the real driver would. */
        private Map<String, Object> project(Map<String, Object> row, String sql) {
            var out = new HashMap<String, Object>();
            for (String col : new String[]{"id", "email", "password_hash", "display_name", "created_at"}) {
                if (sql.contains(col)) out.put(col, row.get(col));
            }
            return out;
        }
    }

    private static ResultSet fakeResultSet(List<Map<String, Object>> rows) {
        var it = rows.iterator();
        @SuppressWarnings("unchecked")
        var current = (Map<String, Object>[]) new Map[1];
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
            FakeDb.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    boolean has = it.hasNext();
                    if (has) current[0] = it.next();
                    yield has;
                }
                case "getString" -> {
                    Object v = current[0].get((String) args[0]);
                    yield v == null ? null : v.toString();
                }
                case "getTimestamp" -> current[0].get((String) args[0]);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
