package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health (D-C20). Liveness, {@code GET /health} (E-60), checks nothing but the process, so a
 * database outage never makes the platform restart a healthy container. Readiness,
 * {@code GET /health/ready} (E-61), reports each dependency: {@code 200} when all are up,
 * {@code 503} otherwise, with the same body. Only {@code database} for now: {@code broker}
 * joins with the event consumer of the saga. Both are anonymous.
 */
@RestController
public class HealthController {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final List<ReadinessCheck> checks;

    public HealthController(List<ReadinessCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    /** Body of E-61: {@code {"status": "DOWN", "checks": {"database": "DOWN"}}}. */
    public record Readiness(String status, Map<String, String> checks) {
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", UP);
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Readiness> ready() {
        Map<String, String> results = new LinkedHashMap<>();
        checks.forEach(check -> results.put(check.name(), check.isUp() ? UP : DOWN));
        boolean allUp = !results.containsValue(DOWN);
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Readiness(allUp ? UP : DOWN, results));
    }
}
