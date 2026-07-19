package dev.minibank.ledger;

import dev.b4rruf3t.sso.client.AudienceAuth;
import dev.b4rruf3t.sso.client.SsoUser;

import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Function;

/**
 * THE ADAPTER · the estate's identity on one side, this bank's customer ids
 * on the other, and nothing in between that HttpApi has to learn.
 *
 * Two halves, and the split is the point:
 *
 *   AudienceAuth              "which SSO subject, if any" · signatures,
 *   (sso-client, shared)      JWKS, expiry, issuer, audience. Shared with
 *                             every other app on the estate, and hardened by
 *                             its own 33 tests. Not reimplemented here.
 *
 *   Directory.customerForSso  "which customer of OURS is that" · this bank's
 *   (this service)            own sso_customers table. Nobody else's problem
 *                             and nobody else's schema.
 *
 * A shop token and a bank token are both valid SSO tokens. Only the audience
 * separates them, which is why AUDIENCE below is not a parameter with a
 * sensible default: it is the one string that stops mart.b4rruf3t.com's
 * credentials from opening the bank, and a default is how such a string ends
 * up wrong in one environment and right in the others.
 *
 * WHY THIS CLASS EXISTS AT ALL, rather than the four-line lambda the seam was
 * originally sketched with: AudienceAuth.authenticate returns an empty
 * Optional for "no header" and for "forged signature" alike, deliberately, on
 * the oracle argument in its own javadoc. This bank needs to tell those apart
 * so a broken credential can be refused rather than silently demoted to
 * anonymous, so the header inspection that sso-client folds in has to be
 * unfolded here. It is done by reading the header, never by asking the
 * validator why it said no.
 */
public final class BankAuth implements SsoIdentity {

    /** This service's audience. A token minted for any other app is not ours. */
    public static final String AUDIENCE = "bank.b4rruf3t.com";

    /** Where the estate's identity actually lives. */
    public static final String DEFAULT_ISSUER = "https://auth.b4rruf3t.com";

    private final AudienceAuth auth;

    /** Production: keys come from the issuer's live JWKS endpoint. */
    public BankAuth(String issuer) {
        this.auth = new AudienceAuth(issuer, AUDIENCE);
    }

    /**
     * Tests: the same validator against a fixed key instead of live JWKS.
     *
     * This exists so the lesson tests can mint REAL RS256 tokens and put them
     * through the real signature check, rather than through a stub that
     * agrees with them by construction. A stub proves the wiring; only a real
     * signature proves the wiring is wired to a validator.
     */
    public BankAuth(String issuer, String audience, Function<String, RSAPublicKey> keyResolver) {
        this.auth = new AudienceAuth(issuer, audience, keyResolver);
    }

    @Override
    public Verdict verdict(String authorizationHeader) {
        // NO HEADER IS NOT A FAILED CHECK. This is the permissive rollout in
        // one branch: the public demo sends nothing, and it must come out the
        // far side of HttpApi.handle byte for byte as it did before SSO.
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Verdict.ABSENT;
        }

        // Something was presented. From here every exit is either Known or
        // Rejected · there is no path back to anonymous, because a caller who
        // presents a credential has asked to be held to it.
        if (!authorizationHeader.startsWith("Bearer ")) {
            return new Verdict.Rejected("not a Bearer credential");
        }

        Optional<SsoUser> user = auth.authenticate(authorizationHeader);
        if (user.isEmpty()) {
            // sso-client will not say which check failed, on purpose, and this
            // class does not guess. "Signature, issuer, audience or expiry"
            // is an honest reason string precisely because it does not claim
            // to know which · a guess here would be a lie in the log on the
            // one day somebody is debugging a rotation at 3am.
            return new Verdict.Rejected("token failed validation for audience " + AUDIENCE);
        }

        String sub = user.get().sub();
        try {
            // NULL IS A GOOD ANSWER HERE. A signed-in visitor with no account
            // at this bank is Known(sub, null): a 200, the anonymous
            // behaviour, and emphatically not somebody else's customer id.
            return new Verdict.Known(sub, Directory.customerForSso(sub));
        } catch (SQLException e) {
            // OUR outage, not their bad credential, so it must not become a
            // 401. The token is good; the table that says what it means to us
            // is unreachable. Degrading to a Known visitor gives exactly the
            // pre-SSO behaviour for this request, while a Rejected here would
            // turn one unreachable directory into an estate-wide credential
            // failure · every app's tokens "stop working" at once, which is
            // the single most misleading outage this service could produce.
            System.err.println("sso: directory unreachable, " + sub
                    + " served as a visitor for this request · " + e);
            return new Verdict.Known(sub, null);
        }
    }
}
