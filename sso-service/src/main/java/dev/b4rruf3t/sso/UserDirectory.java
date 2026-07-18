package dev.b4rruf3t.sso;

import java.sql.*;
import java.util.Optional;

/**
 * User directory: registration, lookup, authentication.
 * Postgres-backed, raw JDBC, no ORM.
 */
public final class UserDirectory {
    private final ConnectionSource db;

    public UserDirectory(ConnectionSource db) {
        this.db = db;
    }

    /** Register a new user. Returns the user ID, or empty if email taken. */
    public Optional<String> register(String email, String password, String displayName) {
        String id = "usr_" + generateId();
        String hash = hashPassword(password);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, email);
            ps.setString(3, hash);
            ps.setString(4, displayName);
            ps.executeUpdate();
            return Optional.of(id);
        } catch (SQLException e) {
            if (e.getMessage().contains("unique constraint")) {
                return Optional.empty();
            }
            throw new RuntimeException("registration failed", e);
        }
    }

    /** Authenticate by email + password. Returns user ID if valid. */
    public Optional<String> authenticate(String email, String password) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, password_hash FROM users WHERE email = ?")) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            String hash = rs.getString("password_hash");
            return verifyPassword(password, hash)
                ? Optional.of(rs.getString("id"))
                : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("authentication failed", e);
        }
    }

    /** Look up a user by ID. */
    public Optional<User> findById(String id) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, email, display_name, created_at FROM users WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new User(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant()
            ));
        } catch (SQLException e) {
            throw new RuntimeException("user lookup failed", e);
        }
    }

    /** Look up a user by email. */
    public Optional<User> findByEmail(String email) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, email, display_name, created_at FROM users WHERE email = ?")) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new User(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant()
            ));
        } catch (SQLException e) {
            throw new RuntimeException("user lookup failed", e);
        }
    }

    // --- password hashing (bcrypt-like, simplified for demo) ---
    // In production, use a real bcrypt library. For this learning project,
    // we use SHA-256 with a per-user salt to demonstrate the pattern.
    private String hashPassword(String password) {
        String salt = generateId();
        return salt + ":" + sha256(salt + password);
    }

    private boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) return false;
        return sha256(parts[0] + password).equals(parts[1]);
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String generateId() {
        return Long.toHexString(System.currentTimeMillis()) +
               Long.toHexString((long) (Math.random() * Long.MAX_VALUE)).substring(0, 8);
    }

    /** A user record from the directory. */
    public record User(String id, String email, String displayName, java.time.Instant createdAt) {}
}
