package dev.lseg.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DoubleEntryLedger API")
                        .version("v1")
                        .description(
                                """
                                An append-only double-entry ledger.

                                Money is always an integer count of minor units: `amountMinor` is the only \
                                field the server reads, and `amount` is a display string it ignores on input.

                                Every write endpoint requires an `Idempotency-Key`. A repeat of the same \
                                request returns `200` with `Idempotency-Replayed: true` and the original \
                                body, rather than posting again.

                                Errors are RFC 9457 problem+json and carry the `requestId` that ties the \
                                call to the logs and, if it posted, to the journal row.
                                """)
                        .license(new License().name("MIT")));
    }
}
