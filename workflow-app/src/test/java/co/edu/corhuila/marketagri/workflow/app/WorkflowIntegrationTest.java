package co.edu.corhuila.marketagri.workflow.app;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the integration tests: a real PostgreSQL with the workflow schema of db/,
 * reached through TEST_DATABASE_URL (Anexo C, "Pruebas"); skipped when the variable is not set.
 * The URL carries the workflow_app user and password: `bash scripts/test-database.sh` prints one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
abstract class WorkflowIntegrationTest extends WorkflowSpringTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        // Empty: the user and password come from the URL itself.
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
    }
}
