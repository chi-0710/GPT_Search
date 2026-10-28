package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A6-04: model settings must never expose API keys in logs or exception text. */
class SecretRedactionTest {
    @Test
    void modelSettingsRedactApiKey() {
        var settings = CompatibleModelFactory.settings("openai", "gpt-test",
                Map.of("OPENAI_API_KEY", "super-secret-key"));
        String rendered = settings.toString();
        assertFalse(rendered.contains("super-secret-key"), rendered);
        assertTrue(rendered.contains("REDACTED"), rendered);
    }

    @Test
    void unsupportedProviderMessageKeepsProviderButNoCredentials() {
        var error = new UnsupportedProviderException(ProviderCatalog.Kind.CHAT, "bedrock");
        assertTrue(error.getMessage().contains("bedrock"));
        assertFalse(error.getMessage().toLowerCase().contains("key"));
    }
}
