package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness, {@code GET /health} (E-60, D-C20): checks nothing but the process, so a database
 * outage never makes the platform restart a healthy container. Anonymous.
 */
@RestController
public class HealthController {

    private static final String UP = "UP";

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", UP);
    }
}
