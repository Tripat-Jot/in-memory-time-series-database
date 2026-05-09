package com.tsdb.memtsdb.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit OpenAPI bean (optional with springdoc; here for interview-visible customization).
 * <p>
 * {@code @Configuration} marks a class that contributes one or more {@code @Bean} methods to the Spring context.
 * Think of it as a named DI registration module (similar to {@code services.AddSwaggerGen(...)} in Startup.cs).
 * </p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI memtsdbOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MemTSDB API")
                        .version("v1")
                        .description("""
                                In-memory time-series database REST API.
                                Tag values are interned to integers at ingestion for heap efficiency (Flyweight-style dedup).
                                """));
    }
}
