package io.researchrunner.infrastructure;

/** Persisted bytes only; downloads never resolve a user-controlled filesystem path. */
public record StoredFile(String filename, String mimeType, String sha256, byte[] content) { }
