package co.edu.corhuila.marketagri.workflow.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The pool against the real schema: workflow_app, its search_path and the limits of Anexo C. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowPersistenceIT extends WorkflowIntegrationTest {

    @Autowired
    private MockMvc http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void readyIs200WithTheDatabaseUp() throws Exception {
        http.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.database").value("UP"));
    }

    @Test
    void connectsAsWorkflowAppInsideTheWorkflowSchema() {
        Map<String, Object> session = jdbc.queryForMap("SELECT current_user AS who, current_schema() AS schema");

        assertThat(session).containsEntry("who", "workflow_app").containsEntry("schema", "workflow");
    }

    @Test
    void seesTheTablesOfTheSaga() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_instance", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_step", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Long.class)).isNotNull();
    }

    @Test
    void everyConnectionCarriesTheStatementTimeout() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void aStatementLongerThanTheLimitIsCancelled() {
        assertThatThrownBy(() -> jdbc.execute("SELECT pg_sleep(6)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("statement timeout");
    }
}
