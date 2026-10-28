package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProviderCatalogTest {
    @Test
    void routeACompatibleChatProvidersAreUsableButNotMarkedImplemented() {
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "deepseek"));
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "dashscope"));
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.CHAT, "openai").status());
    }

    @Test
    void knownButUnmigratedProviderThrowsExplicit501Signal() {
        var unsupported = assertThrows(UnsupportedProviderException.class,
                () -> ProviderCatalog.require(ProviderCatalog.Kind.CHAT, "gigachat"));
        assertEquals("gigachat", unsupported.provider());
        assertEquals(ProviderCatalog.Kind.CHAT, unsupported.kind());
        assertFalse(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "gigachat"));
    }

    @Test
    void anthropicIsRegisteredAfterNativeAdapter() {
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "anthropic"));
    }

    @Test
    void unknownProviderIsAlsoUnsupported() {
        assertThrows(UnsupportedProviderException.class,
                () -> ProviderCatalog.require(ProviderCatalog.Kind.CHAT, "unknown-model-vendor"));
    }

    @Test
    void searchAndImageCoverageMatchesRouteAPlan() {
        assertEquals(ProviderCatalog.Status.IMPLEMENTED,
                ProviderCatalog.entry(ProviderCatalog.Kind.SEARCH, "tavily").status());
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.SEARCH, "brave").status());
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.SEARCH, "getxapi").status());
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.SEARCH, "xquik").status());
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.IMAGE, "google").status());
        assertEquals(ProviderCatalog.Status.NOT_MIGRATED,
                ProviderCatalog.entry(ProviderCatalog.Kind.IMAGE, "unknown-image-vendor").status());
        assertTrue(ProviderCatalog.snapshot().containsKey(ProviderCatalog.Kind.EMBEDDING));
    }

    /** Regression for "catalog says usable but the factory returns 501". */
    @Test
    void everyUsableChatProviderBuildsThroughTheRealFactory() throws Exception {
        for (var entry : ProviderCatalog.snapshot().get(ProviderCatalog.Kind.CHAT).entrySet()) {
            if (entry.getValue().equals("not_migrated")) {
                var env = new MockEnvironment().withProperty("FAST_LLM", entry.getKey() + ":model-x");
                assertThrows(UnsupportedProviderException.class,
                        () -> new ConfiguredChatModels(env).binding("FAST", 64),
                        entry.getKey() + " 应显式 501");
                continue;
            }
            var env = chatEnvironment(entry.getKey());
            var binding = new ConfiguredChatModels(env).binding("FAST", 64);
            assertEquals(entry.getKey(), binding.provider(), entry.getKey() + " 应能真正构建");
        }
    }

    @Test
    void everyUsableSearchProviderIsSelectableAndUnmigratedOnesAreNotOffered() {
        var env = new MockEnvironment().withProperty("RETRIEVER_ENDPOINT", "http://localhost:9999/custom");
        for (var entry : ProviderCatalog.snapshot().get(ProviderCatalog.Kind.SEARCH).entrySet()) {
            if (entry.getValue().equals("not_migrated")) continue;
            try {
                SearchProviderFactory.create(entry.getKey(), env, new ObjectMapper(), Map.of());
            } catch (UnsupportedProviderException e) {
                throw new AssertionError(entry.getKey() + " 被目录标为可用，但工厂返回 501", e);
            } catch (RuntimeException e) {
                // Missing optional configuration is fine; only a 501 contradicts the catalog.
            }
        }
        assertThrows(UnsupportedProviderException.class,
                () -> SearchProviderFactory.create("unknown-search-vendor", env, new ObjectMapper(), Map.of()));
    }

    @Test
    void everyUsableEmbeddingProviderIsConfiguredAndTheRestRejectExplicitly() {
        var keys = Map.of(
                "openai", "OPENAI_API_KEY", "dashscope", "DASHSCOPE_API_KEY",
                "aimlapi", "AIMLAPI_API_KEY", "minimax", "MINIMAX_API_KEY",
                "nebius", "NEBIUS_API_KEY", "openrouter", "OPENROUTER_API_KEY",
                "google_genai", "GOOGLE_API_KEY", "ollama", "OLLAMA_API_KEY");
        for (var entry : ProviderCatalog.snapshot().get(ProviderCatalog.Kind.EMBEDDING).entrySet()) {
            if (entry.getValue().equals("not_migrated")) {
                assertThrows(UnsupportedProviderException.class,
                        () -> ProviderCatalog.require(ProviderCatalog.Kind.EMBEDDING, entry.getKey()),
                        entry.getKey() + " 应显式 501");
                continue;
            }
            String keyVariable = keys.get(entry.getKey());
            assertTrue(keyVariable != null, entry.getKey() + " 缺少测试密钥映射");
            var settings = CompatibleModelFactory.settings(entry.getKey(), "m",
                    Map.of(keyVariable, "synthetic"));
            assertTrue(settings.endpoint().getHost() != null);
        }
    }

    @Test
    void browserAndNodriverRemainExplicitWrittenScopeReduction() {
        for (String provider : List.of("browser", "nodriver")) {
            assertEquals(ProviderCatalog.Status.NOT_MIGRATED,
                    ProviderCatalog.entry(ProviderCatalog.Kind.SCRAPER, provider).status());
            assertThrows(UnsupportedProviderException.class,
                    () -> ScraperFactory.requireSupported(provider));
            assertThrows(UnsupportedProviderException.class,
                    () -> ScraperFactory.create(provider, new MockEnvironment(), new ObjectMapper()));
        }
        assertEquals(ProviderCatalog.Status.PROTOCOL_FOUNDATION,
                ProviderCatalog.entry(ProviderCatalog.Kind.SCRAPER, "bs").status());
    }

    private static MockEnvironment chatEnvironment(String provider) throws Exception {
        var env = new MockEnvironment().withProperty("FAST_LLM", provider + ":model-x");
        return switch (provider) {
            case "openai" -> env.withProperty("OPENAI_API_KEY", "synthetic");
            case "deepseek" -> env.withProperty("DEEPSEEK_API_KEY", "synthetic");
            case "dashscope" -> env.withProperty("DASHSCOPE_API_KEY", "synthetic");
            case "atlascloud" -> env.withProperty("ATLASCLOUD_API_KEY", "synthetic");
            case "openrouter" -> env.withProperty("OPENROUTER_API_KEY", "synthetic");
            case "aimlapi" -> env.withProperty("AIMLAPI_API_KEY", "synthetic");
            case "forge" -> env.withProperty("FORGE_API_KEY", "synthetic");
            case "avian" -> env.withProperty("AVIAN_API_KEY", "synthetic");
            case "minimax" -> env.withProperty("MINIMAX_API_KEY", "synthetic");
            case "nebius" -> env.withProperty("NEBIUS_API_KEY", "synthetic");
            case "groq" -> env.withProperty("GROQ_API_KEY", "synthetic");
            case "mistralai" -> env.withProperty("MISTRAL_API_KEY", "synthetic");
            case "xai" -> env.withProperty("XAI_API_KEY", "synthetic");
            case "google_genai" -> env.withProperty("GOOGLE_API_KEY", "synthetic");
            case "anthropic" -> env.withProperty("ANTHROPIC_API_KEY", "synthetic");
            case "vllm_openai" -> env.withProperty("VLLM_OPENAI_API_KEY", "synthetic")
                    .withProperty("VLLM_OPENAI_API_BASE", "http://localhost:8000/v1");
            case "azure_openai" -> env.withProperty("AZURE_OPENAI_API_KEY", "synthetic")
                    .withProperty("AZURE_OPENAI_ENDPOINT", "https://example.openai.azure.com");
            case "cohere" -> env.withProperty("COHERE_API_KEY", "synthetic");
            case "fireworks" -> env.withProperty("FIREWORKS_API_KEY", "synthetic");
            case "together" -> env.withProperty("TOGETHER_API_KEY", "synthetic");
            case "litellm" -> env.withProperty("LITELLM_API_KEY", "synthetic")
                    .withProperty("LITELLM_BASE_URL", "http://localhost:4000/v1");
            case "netmind" -> env.withProperty("NETMIND_API_KEY", "synthetic");
            case "huggingface" -> env.withProperty("HUGGINGFACE_API_KEY", "synthetic");
            case "google_vertexai" -> {
                KeyPair keyPair = VertexModelsTest.rsaKeyPair();
                String account = new ObjectMapper().writeValueAsString(Map.of(
                        "type", "service_account",
                        "client_email", "sa@proj.iam.gserviceaccount.com",
                        "private_key", VertexModelsTest.pem(keyPair),
                        "token_uri", "https://oauth2.googleapis.com/token",
                        "project_id", "proj"));
                yield env.withProperty("GOOGLE_VERTEX_CREDENTIALS", account)
                        .withProperty("GOOGLE_CLOUD_PROJECT", "proj");
            }
            case "bedrock" -> env
                    .withProperty("FAST_LLM", "bedrock:anthropic.claude-3-5-sonnet-20240620-v1:0")
                    .withProperty("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE")
                    .withProperty("AWS_SECRET_ACCESS_KEY", "synthetic");
            case "ollama" -> env;
            default -> env;
        };
    }
}
