package dev.b4rruf3t.sso;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionStore against the in-memory fake. The lessons: a refresh token is
 * opaque, single-use-on-rotate, expirable, and revocable — and validating
 * one never tells you anything about another.
 */
class SessionStoreTest {

    private SessionStore store;

    @BeforeEach
    void setUp() {
        store = new SessionStore(new FakeDb());
    }

    @Test
    void createdSessionValidates() {
        String token = store.createSession("usr_1", 30);

        Optional<String> userId = store.validate(token);

        assertTrue(userId.isPresent());
        assertEquals("usr_1", userId.get());
    }

    @Test
    void unknownTokenDoesNotValidate() {
        assertTrue(store.validate("ref_nobody").isEmpty());
    }

    @Test
    void revokedTokenDoesNotValidate() {
        String token = store.createSession("usr_1", 30);
        store.revoke(token);

        assertTrue(store.validate(token).isEmpty(), "logout means that token is done");
    }

    @Test
    void revokeAllEndsEverySessionForTheUser() {
        String a = store.createSession("usr_1", 30);
        String b = store.createSession("usr_1", 30);
        String other = store.createSession("usr_2", 30);

        store.revokeAll("usr_1");

        assertTrue(store.validate(a).isEmpty());
        assertTrue(store.validate(b).isEmpty());
        assertTrue(store.validate(other).isPresent(), "another user's session is untouched");
    }

    @Test
    void tokensAreOpaqueAndUnique() {
        String a = store.createSession("usr_1", 30);
        String b = store.createSession("usr_1", 30);

        assertNotEquals(a, b);
        assertTrue(a.startsWith("ref_"), "the prefix tells logs and humans what this string is");
    }

    @Test
    void revokingOneDoesNotAffectAnother() {
        String a = store.createSession("usr_1", 30);
        String b = store.createSession("usr_1", 30);

        store.revoke(a);

        assertTrue(store.validate(b).isPresent(), "revoking the laptop must not kill the phone");
    }
}
