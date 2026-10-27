package io.researchrunner.research;

import java.util.List;

public interface SearchGateway {
    record Hit(String url, String title, String content) { }
    List<Hit> search(String query, List<String> domains, int limit) throws Exception;
}
