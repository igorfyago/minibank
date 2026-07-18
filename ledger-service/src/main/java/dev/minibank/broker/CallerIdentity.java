package dev.minibank.broker;

import java.util.Optional;

/**
 * WHO IS CALLING · the broker's one question about identity.
 *
 * The estate's SSO lives at auth.b4rruf3t.com and the Java validator is
 * dev.b4rruf3t.sso.client.SsoClient. This service does not depend on it,
 * and that is deliberate rather than lazy:
 *
 *   1. The broker needs ONE fact ("which customer, if any") out of a
 *      library that does signature verification, JWKS fetching, clock skew
 *      and audience matching. Depending on the whole thing to ask one
 *      question couples this service's build to a subsystem still in
 *      flight, for no gain.
 *
 *   2. It keeps the security-critical logic · which customer's data gets
 *      served · testable without minting real RS256 tokens. The tests below
 *      exercise the rule that actually leaks data if it is wrong.
 *
 * The adapter, once sso-client is a resolvable artifact, is the whole of it:
 *
 *     CallerIdentity sso(BankAuth auth) {
 *         return header -> auth.authenticate(header).flatMap(auth::customerFor);
 *     }
 *
 * PERMISSIVE BY DEFAULT, per the rollout directive: no token means no
 * identity, and no identity means the request behaves exactly as it did
 * before SSO existed. Enforcement is a separate, later, human decision.
 */
@FunctionalInterface
public interface CallerIdentity {

    /**
     * The customer this Authorization header proves, if it proves one.
     *
     * Empty covers every failure the same way on purpose: no header, wrong
     * scheme, expired signature, a token minted for another app's audience.
     * The caller cannot tell those apart and must not act differently on
     * them · a service that answers differently for "no token" and "bad
     * token" is an oracle for probing which tokens exist.
     */
    Optional<Long> customerFor(String authorizationHeader);

    /** Nobody is ever identified. The behaviour of this service today. */
    CallerIdentity ANONYMOUS = header -> Optional.empty();

    /**
     * THE RULE THAT MATTERS · the token wins over the request.
     *
     * Every endpoint here takes a customer id from the query string or the
     * body, because until now there was nothing better and nothing to
     * protect. The moment a token can identify someone, that parameter
     * becomes an instruction to read somebody else's book, and honouring it
     * is a textbook IDOR: valid token for A, ?customer=B, and the service
     * hands over B.
     *
     * The dangerous property is that it stays invisible during a permissive
     * rollout. Nothing 401s, every existing test passes, the anonymous demo
     * works · and the hole only opens on the day enforcement is switched on
     * and real accounts exist, which is the day everyone has stopped
     * watching the auth work.
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
