package dev.lseg.ledger.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time as a dependency rather than a static call.
 *
 * <p>{@code LocalDate.now()} scattered through the code makes "reject postdated
 * entries" and "balance as of today" untestable without waiting for midnight.
 * Injecting a {@link Clock} lets a test fix the date and assert the boundary.
 */
@Configuration
class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
