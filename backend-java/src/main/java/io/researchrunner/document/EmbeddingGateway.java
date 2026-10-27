package io.researchrunner.document;

import java.util.List;

/** Provider boundary used by indexing and retrieval, replaceable in offline integration tests. */
public interface EmbeddingGateway {
    record Profile(String provider,String model,int dimensions) { }
    Profile configuration();
    List<float[]> embed(List<String> texts);
}
