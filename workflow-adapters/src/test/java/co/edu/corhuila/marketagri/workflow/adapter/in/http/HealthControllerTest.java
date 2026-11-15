package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** E-60 and E-61 (D-C20): liveness checks nothing; readiness reports every dependency. */
class HealthControllerTest {

    private static ReadinessCheck check(String name, boolean up) {
        return new ReadinessCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isUp() {
                return up;
            }
        };
    }

    @Test
    void livenessIsUpEvenWhenADependencyIsDown() {
        HealthController controller = new HealthController(List.of(check("database", false)));

        assertThat(controller.health()).containsEntry("status", "UP");
    }

    @Test
    void readyIs200WhenEveryDependencyIsUp() {
        ResponseEntity<HealthController.Readiness> response =
                new HealthController(List.of(check("database", true))).ready();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .isEqualTo(new HealthController.Readiness("UP", Map.of("database", "UP")));
    }

    @Test
    void readyIs503WithTheSameBodyWhenADependencyIsDown() {
        ResponseEntity<HealthController.Readiness> response =
                new HealthController(List.of(check("database", false))).ready();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .isEqualTo(new HealthController.Readiness("DOWN", Map.of("database", "DOWN")));
    }

    @Test
    void everyDependencyIsReportedByItsName() {
        ResponseEntity<HealthController.Readiness> response = new HealthController(
                List.of(check("database", true), check("other", false))).ready();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().checks())
                .containsExactly(Map.entry("database", "UP"), Map.entry("other", "DOWN"));
    }
}
