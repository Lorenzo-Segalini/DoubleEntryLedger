package dev.lseg.ledger.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id.
 *
 * <p>The same value is echoed as {@code X-Request-Id}, put in the logging MDC,
 * and stored on {@code journal_entry.request_id}. Given a row in the back office
 * you can therefore find the exact request and log lines that produced it, which
 * is most of what "why does this entry exist" means in practice.
 *
 * <p>A caller-supplied header is honoured so a client can correlate across its
 * own systems, but it is length-capped: this value reaches the logs and the
 * database, and unbounded caller-controlled text in both is not something to
 * accept on trust.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "ledger.requestId";
    public static final String UNKNOWN = "unknown";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String supplied = request.getHeader(HEADER);
        String requestId = supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH
                ? UUID.randomUUID().toString()
                : supplied;

        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
