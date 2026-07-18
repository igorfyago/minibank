package dev.b4rruf3t.sso;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserDirectory against an in-memory fake — proves the logic without
 * needing Postgres. The SQL is exercised in integration (docker-compose).
 */
class UserDirectoryTest {

    private UserDirectory dir;
    private FakeDb db;

    @BeforeEach
    void setUp() {
        db = new FakeDb();
        dir = new UserDirectory(db);
    }

    @Test
    void registerCreatesAUser() {
        Optional<String> id = dir.register("igor@b4rruf3t.com", "secret", "Igor");

        assertTrue(id.isPresent());
        assertTrue(id.get().startsWith("usr_"));
    }

    @Test
    void duplicateEmailIsRejected() {
        dir.register("igor@b4rruf3t.com", "secret", "Igor");
        Optional<String> second = dir.register("igor@b4rruf3t.com", "other", "Igor2");

        assertTrue(second.isEmpty(), "the same email twice is a conflict, not a second user");
    }

    @Test
    void authenticateWithCorrectPassword() {
        dir.register("igor@b4rruf3t.com", "secret", "Igor");

        Optional<String> id = dir.authenticate("igor@b4rruf3t.com", "secret");

        assertTrue(id.isPresent());
    }

    @Test
    void authenticateWithWrongPasswordFails() {
        dir.register("igor@b4rruf3t.com", "secret", "Igor");

        Optional<String> id = dir.authenticate("igor@b4rruf3t.com", "wrong");

        assertTrue(id.isEmpty());
    }

    @Test
    void authenticateUnknownEmailFails() {
        Optional<String> id = dir.authenticate("nobody@b4rruf3t.com", "secret");

        assertTrue(id.isEmpty());
    }

    @Test
    void findByIdReturnsTheUser() {
        String id = dir.register("igor@b4rruf3t.com", "secret", "Igor").orElseThrow();

        Optional<UserDirectory.User> user = dir.findById(id);

        assertTrue(user.isPresent());
        assertEquals("igor@b4rruf3t.com", user.get().email());
        assertEquals("Igor", user.get().displayName());
    }

    @Test
    void findByEmailReturnsTheUser() {
        dir.register("igor@b4rruf3t.com", "secret", "Igor");

        Optional<UserDirectory.User> user = dir.findByEmail("igor@b4rruf3t.com");

        assertTrue(user.isPresent());
    }

    @Test
    void findByIdUnknownReturnsEmpty() {
        assertTrue(dir.findById("usr_nobody").isEmpty());
    }

    @Test
    void passwordsAreHashedNotStored() {
        dir.register("igor@b4rruf3t.com", "secret", "Igor");

        String stored = db.passwordHashFor("igor@b4rruf3t.com");
        assertNotEquals("secret", stored, "the password must never be stored in the clear");
        assertTrue(stored.contains(":"), "the hash carries its salt");
    }

    @Test
    void twoUsersWithSamePasswordGetDifferentHashes() {
        dir.register("a@b4rruf3t.com", "same", "A");
        dir.register("b@b4rruf3t.com", "same", "B");

        String hashA = db.passwordHashFor("a@b4rruf3t.com");
        String hashB = db.passwordHashFor("b@b4rruf3t.com");
        assertNotEquals(hashA, hashB, "per-user salt means the same password hashes differently");
    }
}
