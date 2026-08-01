package dev.lseg.ledger.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class RefreshTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenSweeper.class);

    private final RefreshTokenRepository tokens;

    RefreshTokenSweeper(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Expired tokens are credentials that can no longer be used, so keeping them
     * only enlarges what a database leak would expose. Unlike the journal, this
     * table has no historical value — the audit log holds the record of who
     * logged in and when.
     */
    @Scheduled(fixedDelayString = "${ledger.jwt.sweep-interval:PT1H}")
    @Transactional
    void sweep() {
        int removed = tokens.deleteExpired();
        if (removed > 0) {
            log.info("swept {} expired refresh tokens", removed);
        }
    }
}
