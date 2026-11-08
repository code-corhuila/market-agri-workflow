package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reuses the received {@code X-Correlation-Id} (when it is safe to log) or generates one, returns it
 * in the response, keeps it as a request attribute for the error {@code traceId}, and puts it in the
 * MDC so every log line of the request carries it (Anexo C 5.3.9, api-contract D-C28).
 * Runs first, so even an authentication error already has its id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationFilter.class);

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String received = request.getHeader(HEADER);
        String correlationId = received != null && SAFE_ID.matcher(received).matches()
                ? received
                : UUID.randomUUID().toString();

        request.setAttribute(HEADER, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        if (received != null && !received.equals(correlationId)) {
            // Only the length: the rejected value itself is what must never reach the log.
            LOG.warn("Rejected unsafe X-Correlation-Id of {} characters; generated a new one", received.length());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
