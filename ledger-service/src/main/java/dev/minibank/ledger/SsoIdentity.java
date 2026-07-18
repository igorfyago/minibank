package dev.minibank.ledger;

import java.util.Optional;

/**
 * WHO IS CALLING · the ledger's one question about identity.
 *
 * This is deliberately the same interface as dev.minibank.broker.CallerIdentity,
 * written twice rather than shared, because the two services are two
 * deployables that happen to build in one repo. The broker's copy is the
 * broker's contract with its callers; this one is the bank's. If they ever
 * diverge it will be because the bank needed something the broker did not,
 * and a shared type would have made that divergence a refactor instead of an
 * edit. (Read the broker's javadoc for the longer argument · it is the same
 * argument and it was made there first.)
 *
 * The estate's SSO lives at auth.b4rruf3t.com and the Java validator is
 * dev.b4rruf3t.sso.client.SsoClient. This service does not depend on it, and
 * that is deliberate rather than lazy:
 *
 *   1. The bank needs ONE fact ("which customer, if any") out of a library
 *      that does signature verification, JWKS fetching, clock skew and
 *      audience matching. Depending on the whole thing to ask one question
 *      couples this service's build to a subsystem still in flight.
 *
 *   2. It keeps the security-critical logic · WHOSE MONEY GETS SERVED ·
 *      testable without minting real RS256 tokens. SsoIdentityLessonTest
 *      exercises the rule that actually leaks money if it is wrong.
 *
 * THE ADAPTER, once sso-client is a resolvable artifact, is the whole of it ·
 * two lines, wired once in Main before HttpApi.start:
 *
 *     BankAuth auth = new BankAuth(ssoIssuer);
 *     HttpApi.identity(header -> {
 *         try {
 *             return auth.authenticate(header)                 // -> Optional<SsoUser>
 *                        .map(SsoUser::sub)
 *                        .map(Directory::customerForSso)       // throws SQLException
 *                        .filter(java.util.Objects::nonNull);
 *         } catch (SQLException e) {
 *             return Optional.empty();   // directory down = nobody identified
 *         }
 *     });
 *
 * The try is not noise and it is not optional: Directory.customerForSso
 * declares SQLException, and customerFor() declares no checked exception, so
 * a lambda routing through it MUST handle it. A one-liner that reads well and
 * does not compile is worse documentation than four lines that do · the first
 * version of this comment was a one-liner, and a review caught it.
 *
 * and the second half of that lambda is this file's whole reason to exist:
 * BankAuth answers "which SSO subject", Directory.customerForSso turns a
 * subject into a customer id, and nothing in HttpApi has to learn either.
 *
 * PERMISSIVE BY DEFAULT, per the rollout directive: no token means no
 * identity, and no identity means the request behaves exactly as it did
 * before SSO existed · same status, same bytes. Enforcement is a separate,
 * later, human decision, and there is no 401 anywhere in this service until
 * somebody makes it.
 */
@FunctionalInterface
public interface SsoIdentity {

    /**
     * The customer this Authorization header proves, if it proves one.
     *
     * Empty covers every failure the same way on purpose: no header, wrong
     * scheme, expired signature, a token minted for the shop's audience, a
     * valid subject nobody has linked to a bank customer yet. The caller
     * cannot tell those apart and must not act differently on them · a
     * service that answers differently for "no token" and "bad token" is an
     * oracle for probing which tokens exist.
     *
     * IMPLEMENTATIONS MUST NOT THROW. A JWKS fetch timing out is an outage in
     * the directory, not in the bank, and during a permissive rollout it must
     * degrade to "nobody is identified" · which is exactly today's behaviour ·
     * rather than into a 500 on every route at once. HttpApi enforces this
     * with a catch, but the contract is stated here so an implementation does
     * not rely on the catch being there.
     */
    Optional<Long> customerFor(String authorizationHeader);

    /** Nobody is ever identified. The behaviour of this service today. */
    SsoIdentity ANONYMOUS = header -> Optional.empty();

    /**
     * THE RULE THAT MATTERS · the token wins over the request.
     *
     * Every endpoint here takes a customer id from the query string or the
     * body, because until now there was nothing better and nothing to
     * protect. The moment a token can identify someone, that parameter
     * becomes an instruction to read somebody else's book, and honouring it
     * is a textbook IDOR: valid token for A, ?customer=B, and the bank hands
     * over B's statement · or, on /api/transfer, spends B's money.
     *
     * The dangerous property is that it stays invisible during a permissive
     * rollout. Nothing 401s, every existing test passes, the anonymous demo
     * works · and the hole only opens on the day enforcement is switched on
     * and real accounts exist, which is the day everyone has stopped watching
     * the auth work.
     *
     * So the precedence is settled now, while it is cheap:
     *
     *   identified  → the token's customer, and the parameter is ignored
     *   anonymous   → the parameter, exactly as before
     */
    default long resolve(String authorizationHeader, long requested) {
        return customerFor(authorizationHeader).orElse(requested);
    }
}
