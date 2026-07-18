package dev.b4rruf3t.sso;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Simple Postgres connection source. */
public final class Db implements ConnectionSource {
    private final String url;
    private final String user;
    private final String password;

    public Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
