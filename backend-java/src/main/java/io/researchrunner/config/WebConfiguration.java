package io.researchrunner.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    private final boolean writes;

    public WebConfiguration(@Value("${researchrunner.cors-origins}") String origins,
            @Value("${researchrunner.writes-enabled:false}") boolean writes) {
        this.writes=writes;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origins).allowedMethods(writes
                ?new String[]{"GET","POST","PUT","DELETE","OPTIONS"}:new String[]{"GET","OPTIONS"})
                .allowedHeaders("*").allowCredentials(true);
    }
}
