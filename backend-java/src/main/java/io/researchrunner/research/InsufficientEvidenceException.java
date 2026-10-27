package io.researchrunner.research;

/** A retrieval succeeded without usable evidence; transport failures are not this condition. */
public final class InsufficientEvidenceException extends IllegalStateException {
    public InsufficientEvidenceException(String message) { super(message); }
}
