package io.researchrunner.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public WebConfiguration(@Value("${researchrunner.cors-origins}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origins).allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*").allowCredentials(true);
    }
}
