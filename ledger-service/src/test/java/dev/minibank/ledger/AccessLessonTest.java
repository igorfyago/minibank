package dev.minibank.ledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE URL IS NOT AN IDENTITY.
 *
 * bank.b4rruf3t.com is on the public internet and every customer-facing route
 * takes its customer id from the query string or the body. For as long as that
 * number was the only answer to "who is asking", ?customer=10 was one keystroke
 * from ?customer=11 and the bank handed over whichever book you typed. Reading
 * a statement, moving money, placing an order · all of it.
 *
 * These are the lessons that hole owes. They are deliberately in two halves:
 *
 *   BEHAVIOUR · Access decides, in memory, with no database and no HTTP. The
 *   rule is small enough to state exactly, so it is stated exactly.
 *
 *   WIRING · the rule is worth nothing if a handler stops asking. The source
 *   assertions pin the two seams every customer-scoped route resolves through,
 *   so deleting the guard breaks a test rather than quietly reopening the hole.
 *   That second half is not paranoia: the broker shipped to production with
 *   CallerIdentity.ANONYMOUS wired in, which is precisely a correct rule that
 *   nothing called.
 */
class AccessLessonTest {

    /** The estate as it actually stands: igor claimed, the demo cast not. */
    private static final long IGOR = 10, COCO = 11, OSCAR = 12;

    @AfterEach
    void forgetTheTestSet() {
        // static state · a test that installed a set must not decide the next one
        Access.forTest(null);
    }

    // ------------------------------------------------------------- behaviour

    @Test
    void aClaimedAccountIsRefusedOnTheAnonymousPath() {
        Access.forTest(Set.of(IGOR));
        Access.Denied d = assertThrows(Access.Denied.class, () -> Access.guard("10"),
                "an account somebody has claimed must not be readable by naming it in the URL");
        assertFalse(d.unavailable, "this is a decision about the caller, not an outage · 403 not 503");
        assertTrue(d.getMessage().contains("10"));
    }

    @Test
    void anUnclaimedAccountIsStillOpenToEveryone() {
        Access.forTest(Set.of(IGOR));
        // THE HALF THAT IS NOT SECURITY, and it matters as much. The demo cast
        // is a sandbox of invented euros whose whole purpose is to be poked at
        // by strangers with no login. A rule that protected igor by closing the
        // front page would have shipped an outage, not a boundary.
        assertDoesNotThrow(() -> Access.guard("11"));
        assertDoesNotThrow(() -> Access.guard("12"));
        assertFalse(Access.isClaimed(COCO));
        assertFalse(Access.isClaimed(OSCAR));
        assertTrue(Access.isClaimed(IGOR));
    }

    @Test
    void anAccountNobodyHasClaimedYetIsBornPublic() {
        // /api/signup mints ids from MAX+1, so the next visitor to press the
        // button becomes 15. Under an allowlist of demo ids that account would
        // be refused to the very person who just created it, by a rule whose
        // entire purpose was to protect them. Deriving privacy from the link
        // is what makes signup survive this change.
        Access.forTest(Set.of(IGOR));
        assertDoesNotThrow(() -> Access.guard("15"));
    }

    @Test
    void aMalformedOrAbsentIdIsNotASecurityEvent() {
        Access.forTest(Set.of(IGOR));
        // These are 400s from the handler one line later. Refusing them here
        // would file every typo in the log as an attempted breach.
        assertDoesNotThrow(() -> Access.guard(null));
        assertDoesNotThrow(() -> Access.guard(""));
        assertDoesNotThrow(() -> Access.guard("   "));
        assertDoesNotThrow(() -> Access.guard("not-a-number"));
        assertDoesNotThrow(() -> Access.guard("10; DROP TABLE accounts"));
    }

    @Test
    void anUnreadableDirectoryFailsClosedAndSaysSo() {
        Access.forTest(null);      // never successfully loaded
        Access.Denied d = assertThrows(Access.Denied.class, () -> Access.guard("11"),
                "if we cannot tell whether an account is claimed we must not serve it");
        assertTrue(d.unavailable,
                "the caller's credential is not the problem · this must be a 503, because "
                        + "answering 403 sends them to fix an authentication that was never wrong");
    }

    @Test
    void aClaimIsVisibleImmediatelyRatherThanWithinAMinute() {
        Access.forTest(Set.of(IGOR));
        assertDoesNotThrow(() -> Access.guard("15"));
        // Directory.linkSso calls this the moment an account is claimed. Without
        // it a freshly claimed account stays publicly readable until the TTL
        // lapses, which is a hole with a timer on it rather than a closed one.
        Access.forTest(Set.of(IGOR, 15L));
        assertThrows(Access.Denied.class, () -> Access.guard("15"));
    }

    // ---------------------------------------------------------------- wiring

    @Test
    void theBankResolvesEveryCustomerScopedRouteThroughTheGuard() {
        String src = code("main/java/dev/minibank/ledger/HttpApi.java");
        assertTrue(src.contains("Access.guard(requested)"),
                "HttpApi.caller must guard the anonymous path · it is the single seam all "
                        + "18 customer-scoped call sites resolve through, including the debit "
                        + "side of /api/transfer");
        // The guard has to sit AFTER the identified short-circuit. An identified
        // caller resolves to their own id, so guarding them would be asking
        // whether they may read themselves.
        int shortCircuit = src.indexOf("if (identified != null) return identified.toString();");
        int guard = src.indexOf("Access.guard(requested)");
        assertTrue(shortCircuit > 0 && guard > shortCircuit,
                "the guard belongs on the anonymous path only");
    }

    @Test
    void theBrokerAppliesTheSameRuleAsTheBank() {
        String src = code("main/java/dev/minibank/broker/BrokerApi.java");
        assertTrue(src.contains("Access.guard("),
                "a book that is private on bank.b4rruf3t.com and public on "
                        + "broker.b4rruf3t.com is not private");
    }

    @Test
    void aRefusalIsNotReportedAsABug() {
        // 500 is the wrong answer twice over: it reads as a fault in the bank,
        // and it is what a client retries. Both services translate the refusal.
        for (String file : new String[] {
                "main/java/dev/minibank/ledger/HttpApi.java",
                "main/java/dev/minibank/broker/BrokerApi.java" }) {
            String src = code(file);
            assertTrue(src.contains("Access.Denied"), file + " must catch the refusal");
            assertTrue(src.contains("403"), file + " must answer 403 when the account is not yours");
            assertTrue(src.contains("503"), file + " must answer 503 when it could not check");
        }
    }

    @Test
    void theFrontEndsCanActuallyOpenAPrivateAccount() {
        // The other half of enforcement, and the half that decides whether this
        // change is a security fix or a lockout. A signed-in owner whose browser
        // sends no credential is indistinguishable from a stranger, so without
        // this the first thing the guard does is refuse igor his own account.
        String lib = code("main/resources/web/lib.js");
        assertTrue(lib.contains("ensureToken"), "the bank's api() must be able to carry a token");
        assertTrue(lib.contains("credentials: 'include'"),
                "the estate cookie is HttpOnly · it can only be spent on a credentialed refresh");

        String index = code("main/resources/web/index.html");
        assertTrue(index.contains("MB.makeApi(fetch, MB.ensureToken)"),
                "lib.js growing the ability is not the same as index.html using it");

        // and neither page may default to a claimed account, or the anonymous
        // demo opens on a refusal
        assertFalse(code("main/resources/web-broker/portfolio.html")
                        .contains("params.get('customer') || '10'"),
                "the broker's anonymous default must not be igor's private account");
    }

    /**
     * The source with every comment removed · and this is not tidiness, it is
     * the difference between a test and a decoration.
     *
     * Written the obvious way, these assertions read the raw file and ask
     * whether it contains "Access.guard(requested)". Commenting the call out
     * leaves that string in the file, so the guard could be disabled with two
     * keystrokes and every test here would still pass. Worse, the classes
     * involved carry long javadoc that MENTIONS the guard, the 403 and the 503
     * by name · so these assertions could have been satisfied by prose alone,
     * with the code deleted outright.
     *
     * Proven rather than assumed: commenting out the call in HttpApi.caller and
     * re-running this class fails theBankResolvesEveryCustomerScopedRouteThrough
     * TheGuard. Before this method existed, it passed.
     */
    private static String code(String relative) {
        String src = read(relative);
        src = src.replaceAll("(?s)/\\*.*?\\*/", "");            // block comments and javadoc
        StringBuilder b = new StringBuilder();
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*")) continue;
            b.append(line).append('\n');
        }
        return b.toString();
    }

    private static String read(String relative) {
        Path p = Path.of("src", relative.split("/"));
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("could not read " + p + ": " + e.getMessage(), e);
        }
    }
}
