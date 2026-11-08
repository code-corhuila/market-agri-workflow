package co.edu.corhuila.marketagri.workflow.adapter.out.persistence;

import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Readiness of PostgreSQL: borrows a connection from the pool and asks the driver to validate it. */
public final class JdbcDatabaseCheck implements ReadinessCheck {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcDatabaseCheck.class);
    private static final String NAME = "database";
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public JdbcDatabaseCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException failure) {
            // Hikari reports a pool that cannot start with an unchecked exception.
            LOG.warn("Database not ready: {}", failure.getMessage());
            return false;
        }
    }
}
