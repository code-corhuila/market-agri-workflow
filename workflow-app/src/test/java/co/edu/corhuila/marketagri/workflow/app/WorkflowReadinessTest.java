package co.edu.corhuila.marketagri.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wiring of the composition root without a database: the test properties point the pool to an
 * address nobody listens on. Readiness must say so; liveness must not care (D-C20).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowReadinessTest extends WorkflowSpringTest {

    @Autowired
    private MockMvc http;

    @Autowired
    private DataSource dataSource;

    @Test
    void readyIs503WithTheDatabaseDownWhileLivenessStaysUp() throws Exception {
        http.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.checks.database").value("DOWN"));
        http.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void poolHasTheLimitsOfAnnexC() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
        assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
        assertThat(pool.getConnectionInitSql()).isEqualTo("SET statement_timeout = '5s'");
    }
}
