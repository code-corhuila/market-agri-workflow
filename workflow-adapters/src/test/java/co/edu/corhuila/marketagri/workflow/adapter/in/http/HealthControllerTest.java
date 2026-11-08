package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** E-60 (D-C20): liveness checks nothing but the process. */
class HealthControllerTest {

    @Test
    void livenessIsUp() {
        assertThat(new HealthController().health()).containsEntry("status", "UP");
    }
}
