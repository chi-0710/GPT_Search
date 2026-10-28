package io.researchrunner.research;

import java.util.ArrayList;
import java.util.List;

/** Shared domain-query and result-filtering behavior for HTTP search providers. */
final class SearchSupport {
    private SearchSupport() { }

    static String withDomains(String query, List<String> domains) {
        if (domains == null || domains.isEmpty()) return query;
        List<String> clauses = new ArrayList<>();
        for (String domain : domains) {
            String host = DomainPolicy.hostname(domain, true);
            if (host != null) clauses.add("site:" + host);
        }
        if (clauses.isEmpty()) return query;
        return query + " (" + String.join(" OR ", clauses) + ")";
    }

    /** Applies the same request-scoped domain restriction as the Python receiver. */
    static List<SearchGateway.Hit> filter(List<SearchGateway.Hit> hits, List<String> domains, int limit) {
        DomainPolicy policy = new DomainPolicy(domains);
        List<SearchGateway.Hit> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SearchGateway.Hit hit : hits) {
            if (hit.url() == null || hit.url().isBlank() || !policy.permits(hit.url())) continue;
            if (!seen.add(hit.url())) continue;
            result.add(hit);
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }
}
