package com.travislabs.mjdbcmcp;

/**
 * A refusal the agent is expected to act on, as opposed to a failure.
 *
 * <p>The {@link Kind} exists because collapsing these into one message is a real failure mode
 * (ADR-0004): "this Datasource may not run that class of statement" and "this statement could not be
 * parsed" send an agent down completely different paths, and an agent told only "refused" retries a
 * Capability problem by rewriting syntax forever.
 *
 * <p>Wording follows ADR-0003: a refusal says the Datasource is not configured for something, never
 * that access was denied. Nothing here defends against a hostile agent and no message may imply it
 * does.
 */
public class Refusal extends RuntimeException {

    public enum Kind {
        /** No Datasource by that name. */
        UNKNOWN_DATASOURCE,
        /** The Datasource exists but the operator has disabled it. */
        DATASOURCE_DISABLED,
        /** The statement's class is not among the Datasource's Capabilities. */
        CAPABILITY,
        /** Classification could not parse the SQL, so it is refused rather than passed through. */
        UNPARSEABLE,
        /** More than one statement in one payload. */
        MULTIPLE_STATEMENTS,
        /** Parsed, but not a statement class this server knows how to classify. */
        UNCLASSIFIABLE,
        /** The statement names an object outside the Datasource's Object Allowlist. */
        NOT_ALLOWLISTED,
        /** The tool is explicitly disabled on the target Datasource. */
        DISABLED_TOOL,
        /** Argument the agent supplied is missing or unusable. */
        BAD_ARGUMENT
    }

    /** The refusal category. */
    private final Kind kind;

    /**
     * Constructs a new Refusal with the given kind and detail message.
     *
     * @param kind    the refusal category
     * @param message the detail message describing why the request was refused
     */
    public Refusal(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Returns the refusal kind.
     *
     * @return the refusal category
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Formats the text sent back to the agent, prefixed so the category survives being read as prose.
     *
     * @return the formatted refusal string for the agent
     */
    public String toAgentMessage() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + getMessage();
    }
}
