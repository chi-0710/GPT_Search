package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** A3-05 constructor/credential validation for the Azure OpenAI adapter. */
class AzureModelsTest {
    @Test
    void buildsClientWithDeploymentAndKey() {
        assertNotNull(AzureModels.create("gpt-4o", 1024, 0.2,
                "https://example.openai.azure.com", "synthetic"));
    }

    @Test
    void rejectsMissingOrInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> AzureModels.create("gpt-4o", 1024, 0.2, null, "key"));
        assertThrows(IllegalArgumentException.class,
                () -> AzureModels.create("gpt-4o", 1024, 0.2, "https://example.openai.azure.com", null));
        assertThrows(IllegalArgumentException.class,
                () -> AzureModels.create("gpt-4o", 0, 0.2, "https://example.openai.azure.com", "key"));
        assertThrows(IllegalArgumentException.class,
                () -> AzureModels.create("", 1024, 0.2, "https://example.openai.azure.com", "key"));
    }
}
