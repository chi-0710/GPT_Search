package io.researchrunner.research;

/** Explicit "known provider but not migrated to Java" signal; mapped to HTTP 501. */
public final class UnsupportedProviderException extends RuntimeException {
    private final ProviderCatalog.Kind kind;
    private final String provider;

    public UnsupportedProviderException(ProviderCatalog.Kind kind, String provider) {
        super("Java 后端尚未迁移该供应商：" + kind.name().toLowerCase() + "/" + provider);
        this.kind = kind;
        this.provider = provider;
    }

    public ProviderCatalog.Kind kind() { return kind; }

    public String provider() { return provider; }
}
