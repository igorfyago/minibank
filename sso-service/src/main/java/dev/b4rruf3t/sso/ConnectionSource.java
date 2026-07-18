package dev.b4rruf3t.sso;

import java.sql.Connection;
import java.sql.SQLException;

/** Minimal connection source — copied from minibank's pattern. */
public interface ConnectionSource {
    Connection getConnection() throws SQLException;
}
