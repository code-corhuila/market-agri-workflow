package co.edu.corhuila.marketagri.workflow.app;

import co.edu.corhuila.marketagri.workflow.adapter.in.http.PublicEndpoint;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only routes for the cross-cutting HTTP adapter (error body, validation, correlation).
 * They are kept after the real endpoints exist (E-40, E-46): they test the handler and the filter on
 * their own, so a change in a business route never hides a regression of the contract body.
 * It lives in the test sources: it never reaches the jar. Anonymous, so the token never hides
 * the check under test; the token checks use {@link SecuredProbeController}.
 */
@PublicEndpoint
@RestController
public class ProbeController {

    public record ProbeRequest(@NotBlank(message = "Es obligatorio.") String reference,
                        @NotNull(message = "Es obligatorio.") Long amountCents) {
    }

    @PostMapping("/probe")
    public Map<String, String> create(@RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody ProbeRequest request) {
        return Map.of("reference", request.reference());
    }

    @GetMapping("/probe/{id}")
    public Map<String, String> read(@PathVariable UUID id) {
        return Map.of("id", id.toString());
    }

    @GetMapping("/probe/failure")
    public Map<String, String> failure() {
        throw new IllegalStateException("SELECT * FROM workflow.secret_table");
    }
}
