package com.tsdb.memtsdb;

import com.tsdb.memtsdb.persistence.WALProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Bootstrap type for MemTSDB (composition root).
 * <p>
 * {@code @SpringBootApplication} is a convenience meta-annotation combining:
 * <ul>
 *   <li>{@code @Configuration} — this class can declare {@code @Bean} methods (like a DI module).</li>
 *   <li>{@code @EnableAutoConfiguration} — Spring Boot auto-wires defaults (embedded web server, Jackson, etc.).</li>
 *   <li>{@code @ComponentScan} — scans this package and sub-packages for {@code @Component} stereotypes.</li>
 * </ul>
 * Analogous to {@code WebApplication.CreateBuilder(args).Build().Run()} wiring in ASP.NET Core Minimal APIs / Generic Host,
 * except conventions are attribute-driven and classpath-scan driven rather than explicit {@code AddControllers()} calls
 * (though those still exist implicitly via starters).
 * </p>
 *
 * <h2>Why TSDB workloads care about ordered timestamps</h2>
 * Time-series queries are almost always range scans on time: “last 15 minutes”, “between T1 and T2”.
 * Keeping points ordered by {@code timestamp} turns arbitrary lookups into efficient navigable-map operations
 * ({@code subMap}) instead of scanning every point. This mirrors why metrics systems shard by time and use
 * sorted chunks (e.g., LSM trees, sorted runs): locality + pruning + merge-friendly layouts.
 */
@SpringBootApplication
@EnableConfigurationProperties(WALProperties.class)
public class MemtsdbApplication {

    public static void main(String[] args) {
        // SpringApplication.run bootstraps the ApplicationContext (IoC container) — similar to building the
        // DI ServiceProvider in Microsoft.Extensions.DependencyInjection and starting the host.
        SpringApplication.run(MemtsdbApplication.class, args);
    }
}
