package dev.minibank.ledger;

/**
 * THE ACTIVATION SWITCH · one place to answer "does a credential matter yet".
 *
 * Identity lands wired but toothless on purpose. bank.b4rruf3t.com is a
 * public demo with no logins, the estate's seeded NPC traffic carries no
 * credentials, and a bank that starts refusing them on the day the seam ships
 * has not shipped security, it has shipped an outage. So activation is a
 * deployment decision, made once, read here, and OFF until somebody says
 * otherwise.
 *
 * OFF is the behaviour that predates identity: a caller with no credential is
 * anonymous, and the request's own ?customer= stands. ON means an /api/ route
 * wants a caller who can prove something, and one who cannot is told so.
 *
 * WHAT DOES NOT DEPEND ON THIS SWITCH, and it is the whole reason the switch
 * can be left off safely: a credential that IS presented is always checked,
 * in both modes. A caller who bothers to prove something has asked to be held
 * to it, and honouring a token while ignoring whether it is real would be the
 * worst of both phases · see SsoIdentity.Verdict.Rejected. This switch
 * governs only whether ABSENCE is tolerated.
 *
 * This is deliberately the same shape as minimart's dev.minipay.auth
 * .Enforcement, down to the property-then-environment order, because the two
 * services will be activated by the same person on the same afternoon and a
 * switch that works differently in each is a switch that gets half-thrown.
 *
 * The system property is here because a test needs to flip the switch inside
 * one JVM, and an environment variable cannot be flipped in a running
 * process. The environment variable is how a deployment sets it.
 */
public final class Enforcement {

    public static final String PROPERTY = "bank.identity.enforce";
    public static final String ENV = "BANK_IDENTITY_ENFORCE";

    private Enforcement() {}

    /** True when an /api/ request with no valid credential is refused. */
    public static boolean on() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) v = System.getenv(ENV);
        if (v == null) return false;
        String s = v.trim();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }
}
