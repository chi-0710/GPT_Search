package io.researchrunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {
    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    void convertsSqlalchemyUrlAndCredentials() {
        var environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL",
                "postgresql+asyncpg://gpt_researcher:gpt_researcher@localhost:5432/gpt_researcher");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("jdbc:postgresql://localhost:5432/gpt_researcher",
                environment.getProperty("spring.datasource.url"));
        assertEquals("gpt_researcher", environment.getProperty("spring.datasource.username"));
        assertEquals("gpt_researcher", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void decodesCredentialsAndKeepsQueryParameters() {
        var environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL",
                "postgresql+asyncpg://user:p%40ss@db.example.test:6543/research?sslmode=require");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("jdbc:postgresql://db.example.test:6543/research?sslmode=require",
                environment.getProperty("spring.datasource.url"));
        assertEquals("p@ss", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void leavesJdbcUrlAndExplicitJavaUrlUntouched() {
        var environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL", "postgresql://ignored:ignored@ignored:5432/ignored");
        environment.setProperty("JAVA_DATABASE_URL", "jdbc:postgresql://db:5432/app");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("jdbc:postgresql://db:5432/app", environment.getProperty("spring.datasource.url"));
        assertNull(environment.getProperty("spring.datasource.username"));
    }

    @Test
    void mapsSqlalchemyPoolSettingsToHikari() {
        var environment = new MockEnvironment();
        environment.setProperty("DB_POOL_SIZE", "5");
        environment.setProperty("DB_MAX_OVERFLOW", "10");
        environment.setProperty("DB_POOL_TIMEOUT_SECONDS", "30");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals(15, environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class));
        assertEquals(30_000, environment.getProperty("spring.datasource.hikari.connection-timeout", Integer.class));
    }

    @Test
    void ignoresMalformedPoolValuesInsteadOfFailingStartup() {
        var environment = new MockEnvironment();
        environment.setProperty("DB_POOL_SIZE", "not-a-number");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertNull(environment.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }
}
