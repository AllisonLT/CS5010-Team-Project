package edu.northeastern.cs5010.backend.inference;

/** The ML service could not give a usable answer. No prediction may be made up in its place. */
public class MlException extends RuntimeException {
    public enum Kind {
        /** No connection (service not running). */
        UNAVAILABLE,
        /** Running but not ready, e.g. model artifacts missing (HTTP 503). */
        NOT_READY,
        /** No answer within the read timeout. */
        TIMEOUT,
        /** The service refused the input (HTTP 422). The message is its explanation. */
        REJECTED,
        /** The answer does not match API B. */
        BAD_RESPONSE
    }

    private final Kind kind;

    public MlException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MlException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
