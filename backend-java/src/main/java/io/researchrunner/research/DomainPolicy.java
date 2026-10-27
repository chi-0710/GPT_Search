package io.researchrunner.research;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** An invalid nonempty allow-list must never become an unrestricted search. */
public final class DomainPolicy {
    private final boolean restricted;
    private final List<String> hosts;

    public DomainPolicy(List<String> domains) {
        restricted = domains != null && !domains.isEmpty();
        hosts = domains == null ? List.of() : domains.stream().map(value -> hostname(value, true))
                .filter(value -> value != null).distinct().toList();
    }

    public List<String> hosts() { return hosts; }
    public boolean deniesAll() { return restricted && hosts.isEmpty(); }
    public boolean permits(String url) {
        String host = hostname(url, false);
        return host != null && (!restricted || hosts.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed)));
    }

    public static String hostname(String value, boolean allowBare) {
        if (value == null || value.isBlank()) return null;
        try {
            value = value.strip();
            if (allowBare && !value.contains("://")) value = "https://" + value;
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getRawAuthority() == null) return null;
            String authority = uri.getRawAuthority();
            if (authority.contains("@")) return null;
            String[] parts = authority.split(":", -1);
            if (parts.length > 2) return null;
            if (parts.length == 2 && (Integer.parseInt(parts[1]) < 0 || Integer.parseInt(parts[1]) > 65535)) return null;
            String host = IDN.toASCII(parts[0].replaceAll("\\.+$", ""), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) { return null; }
    }
}
