package dev.lseg.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.lseg.ledger.idempotency.IdempotencyProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
class SchedulingConfig {}
