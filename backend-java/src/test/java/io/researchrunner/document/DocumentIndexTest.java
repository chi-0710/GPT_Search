package io.researchrunner.document;

import io.researchrunner.infrastructure.ResourceMapper;
import io.researchrunner.infrastructure.StoredFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIndexTest {
    @Test void invalidEmbeddingDoesNotCreateAnActiveProfileOrReplaceExistingChunks() {
        var store=mock(DocumentStore.class);var resources=mock(ResourceMapper.class);var embeddings=mock(EmbeddingGateway.class);
        UUID id=UUID.randomUUID();
        when(resources.document(id)).thenReturn(new StoredFile("资料.txt","text/plain","hash","证据".getBytes(StandardCharsets.UTF_8)));
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test","model",3));
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1,0}));
        var index=new DocumentIndex(store,resources,embeddings,1000,100,8,0.35);
        assertThrows(IllegalArgumentException.class,()->index.index(id));
        verify(store,never()).profile(anyString(),anyString(),anyInt());
        verify(store,never()).replace(any(),any(),anyList(),anyList());
        verify(store).failed(eq(id),contains("IllegalArgumentException"));
    }
}
