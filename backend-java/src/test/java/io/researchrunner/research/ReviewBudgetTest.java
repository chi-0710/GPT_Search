package io.researchrunner.research;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ReviewBudgetTest {
    @Test void independentBudgetPreservesExplicitLimitsAndModel() {
        var env=new MockEnvironment().withProperty("STRATEGIC_LLM","deepseek:deepseek-v4-flash")
                .withProperty("DEEPSEEK_API_KEY","synthetic");
        var models=new ConfiguredChatModels(env);
        var review=models.reviewBinding("STRATEGIC","JAVA_FACT_CHECK_TOKEN_LIMIT");
        assertEquals(16000,review.client().getDefaultOptions().getMaxTokens());
        assertEquals("deepseek-v4-flash",review.model());
        assertEquals(8000,models.binding("STRATEGIC",8000).client().getDefaultOptions().getMaxTokens());
        env.withProperty("STRATEGIC_TOKEN_LIMIT","9000");
        assertEquals(9000,models.reviewBinding("STRATEGIC","JAVA_FACT_CHECK_TOKEN_LIMIT").client().getDefaultOptions().getMaxTokens());
        env.withProperty("JAVA_FACT_CHECK_TOKEN_LIMIT","18000");
        assertEquals(18000,models.reviewBinding("STRATEGIC","JAVA_FACT_CHECK_TOKEN_LIMIT").client().getDefaultOptions().getMaxTokens());
        env.withProperty("JAVA_FACT_CHECK_TOKEN_LIMIT","0");
        assertThrows(IllegalArgumentException.class,()->models.reviewBinding("STRATEGIC","JAVA_FACT_CHECK_TOKEN_LIMIT"));
    }
}
