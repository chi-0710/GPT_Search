package io.researchrunner.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts the Python-style {@code DATABASE_URL} in addition to {@code JAVA_DATABASE_URL}.
 *
 * <p>The Python service uses {@code postgresql+asyncpg://user:pass@host:5432/db}, which JDBC
 * cannot parse. This post-processor converts it once, before the DataSource is created, and
 * maps the SQLAlchemy pool settings ({@code DB_POOL_SIZE}, {@code DB_MAX_OVERFLOW},
 * {@code DB_POOL_TIMEOUT_SECONDS}) onto HikariCP. It never logs the URL or credentials.
 */
public final class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String SOURCE_NAME = "databaseUrlCompatibility";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> properties = new LinkedHashMap<>();
        String explicitJdbc = environment.getProperty("JAVA_DATABASE_URL");
        String raw = explicitJdbc != null && !explicitJdbc.isBlank()
                ? explicitJdbc
                : environment.getProperty("DATABASE_URL");
        if (raw != null && !raw.isBlank()) {
            if (raw.startsWith("jdbc:")) {
                properties.put("spring.datasource.url", raw);
            } else {
                Converted converted = convert(raw);
                properties.put("spring.datasource.url", converted.url());
                if (converted.username() != null && !converted.username().isBlank()) {
                    properties.put("spring.datasource.username", converted.username());
                }
                if (converted.password() != null) {
                    properties.put("spring.datasource.password", converted.password());
                }
            }
        }

        Integer poolSize = integer(environment.getProperty("DB_POOL_SIZE"));
        Integer overflow = integer(environment.getProperty("DB_MAX_OVERFLOW"));
        if (poolSize != null && poolSize > 0) {
            int maximum = poolSize + (overflow == null ? 0 : Math.max(0, overflow));
            properties.put("spring.datasource.hikari.maximum-pool-size", maximum);
        }
        Integer timeoutSeconds = integer(environment.getProperty("DB_POOL_TIMEOUT_SECONDS"));
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            properties.put("spring.datasource.hikari.connection-timeout", timeoutSeconds * 1000L);
        }

        if (!properties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, properties));
        }
    }

    record Converted(String url, String username, String password) { }

    /** Converts a SQLAlchemy/Postgres URL into a JDBC URL and extracts URL credentials. */
    static Converted convert(String raw) {
        String normalized = raw.trim();
        normalized = normalized
                .replaceFirst("^postgresql\\+asyncpg://", "postgresql://")
                .replaceFirst("^postgresql\\+psycopg2?://", "postgresql://")
                .replaceFirst("^postgresql\\+asyncpg://", "postgresql://")
                .replaceFirst("^postgres://", "postgresql://");
        if (normalized.startsWith("jdbc:")) {
            return new Converted(normalized, null, null);
        }
        URI uri = URI.create(normalized);
        String user = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL 缺少数据库主机名");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        String jdbc = "jdbc:postgresql://" + host
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + path
                + (query == null || query.isBlank() ? "" : "?" + query);
        return new Converted(jdbc, user, password);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // Run after Spring Boot's config-data post-processors so explicit application.yml
        // values are visible, but before the DataSource auto-configuration reads them.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
