package dev.minibank.ledger;

/**
 * WHO IS CALLING · the ledger's one question about identity.
 *
 * This is deliberately a near-twin of dev.minibank.broker.CallerIdentity,
 * written twice rather than shared, because the two services are two
 * deployables that happen to build in one repo. The broker's copy is the
 * broker's contract with its callers; this one is the bank's. If they ever
 * diverge it will be because the bank needed something the broker did not,
 * and a shared type would have made that divergence a refactor instead of an
 * edit. (Read the broker's javadoc for the longer argument · it is the same
 * argument and it was made there first.) They have now diverged, on exactly
 * that basis: see THREE ANSWERS below.
 *
 * The estate's SSO lives at auth.b4rruf3t.com and the Java validator is
 * dev.b4rruf3t.sso.client.SsoClient. BankAuth is the implementation of this
 * interface that uses it, and Main wires it at boot. Nothing else in this
 * service knows either exists: HttpApi asks this one question, and the answer
 * arrives already translated into this bank's own customer ids.
 *
 * THREE ANSWERS, NOT TWO · and this is the change that has teeth.
 *
 * The first version of this seam returned Optional&lt;Long&gt; and collapsed
 * every failure into empty: no header, wrong scheme, expired signature, a
 * token minted for the shop's audience, a valid subject nobody has linked.
 * The argument for that was an oracle argument, and for one of those cases it
 * is still right · see Verdict.Known below, where it is preserved exactly.
 *
 * For the rest it was wrong, and it was wrong in the direction that costs the
 * most to discover. A caller who presents a credential is not an anonymous
 * caller. Treating a broken one as anonymous means a token that stops working
 * · a rotated key, a botched audience, a clock drifting past exp · degrades
 * silently into "the demo still works", on every route at once, with nothing
 * in the logs and nothing in the metrics. That is how a dead integration
 * survives a month. So a presented credential that does not verify is
 * refused, out loud, with a 401, in both permissive and enforcing modes.
 *
 * The oracle worry does not apply to that, and it is worth being precise
 * about why rather than trusting the shape of the argument. The thing an
 * attacker would want to enumerate is WHICH HUMANS BANK HERE, and that
 * question is answered by the difference between "a valid token I could map"
 * and "a valid token I could not". Those two remain indistinguishable · both
 * are Verdict.Known, both are a 200, both fall back to the request's own
 * parameter. What becomes distinguishable is only "the credential you handed
 * me is not valid here", which the holder of a broken token already knows and
 * an attacker learns nothing from.
 *
 * PERMISSIVE, and the word is load-bearing. Nothing here becomes newly
 * REQUIRED. A request with no Authorization header at all behaves exactly as
 * it did before SSO existed: same status, same bytes, on every route.
 * Requiring a credential is a separate, later, human decision and it lives
 * behind Enforcement, which is off.
 */
@FunctionalInterface
public interface SsoIdentity {

    /**
     * What a credential turned out to be worth.
     *
     * Sealed and matched with a switch in HttpApi rather than probed with
     * instanceof, so that adding a fourth answer becomes a compile error at
     * the one place that decides status codes, instead of a case that quietly
     * falls into the default and behaves like an anonymous request.
     */
    sealed interface Verdict {

        /**
         * Nobody presented anything. The behaviour of this service before SSO
         * existed, and still its overwhelmingly common case: the public demo
         * at bank.b4rruf3t.com has no logins and sends no headers.
         */
        record Absent() implements Verdict {}

        /**
         * Something was presented and this service will not act on it.
         *
         * The reason is carried for the LOG, never for the response body. The
         * caller is told 401 and nothing more, because a validator that
         * explains its refusals is the oracle SsoClient's own javadoc refuses
         * to be. An operator reading stderr, on the other hand, is on this
         * side of the trust boundary and is exactly who needs to know that
         * every token since 09:00 has failed on audience.
         */
        record Rejected(String why) implements Verdict {}

        /**
         * A credential that verified · signature, issuer, audience, expiry.
         *
         * customerId is NULLABLE and that null is the interesting half. It
         * means a real, signed-in visitor who holds no account at this bank:
         * somebody who signed into the shop, followed a link, and is now
         * reading the public pages. That is not an error, it is not a
         * refusal, and it is emphatically not somebody else's account. It is
         * a 200 that falls back to exactly the anonymous behaviour.
         *
         * It is also the case the old collapsed-Optional contract got right,
         * and the reason is unchanged: a bank that answers differently for
         * "a subject I know" and "a subject I do not" is an oracle for
         * enumerating which humans hold accounts here. Known(sub, null) and
         * Known(sub, 42L) must be indistinguishable from outside, and every
         * route below treats them so.
         */
        record Known(String subject, Long customerId) implements Verdict {}

        /** Allocation-free constant for the common case · every request without a header. */
        Verdict ABSENT = new Absent();
    }

    /**
     * The verdict on this request's Authorization header.
     *
     * IMPLEMENTATIONS MUST NOT THROW. A JWKS fetch timing out is an outage in
     * the directory, not in the bank, and it must degrade to Absent rather
     * than into a 500 on every route at once, or (worse) a 401 storm across
     * an estate whose credentials are all perfectly good. HttpApi enforces
     * this with a catch, but the contract is stated here so an implementation
     * does not rely on the catch being there.
     *
     * Note the asymmetry, which is the design: OUR failure to check degrades
     * to anonymous, THEIR failure to present something checkable does not.
     */
    Verdict verdict(String authorizationHeader);

    /** Nobody is ever identified. The behaviour of this service before SSO. */
    SsoIdentity ANONYMOUS = header -> Verdict.ABSENT;

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
     *
     * "Identified" here means Known WITH a customer id. A Known visitor with
     * no account maps to nothing and therefore changes nothing, which is what
     * makes them a visitor rather than a customer with an empty book.
     */
    default long resolve(String authorizationHeader, long requested) {
        return verdict(authorizationHeader) instanceof Verdict.Known k && k.customerId() != null
                ? k.customerId()
                : requested;
    }
}
