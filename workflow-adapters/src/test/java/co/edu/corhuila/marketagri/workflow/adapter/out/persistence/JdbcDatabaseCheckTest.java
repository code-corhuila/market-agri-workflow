package co.edu.corhuila.marketagri.workflow.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The database check of E-61: up only with a valid connection, and never throws. */
class JdbcDatabaseCheckTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final JdbcDatabaseCheck check = new JdbcDatabaseCheck(dataSource);

    @Test
    void isNamedAsTheContractKey() {
        assertThat(check.name()).isEqualTo("database");
    }

    @Test
    void upWithAValidConnectionThatGoesBackToThePool() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        assertThat(check.isUp()).isTrue();
        verify(connection).close();
    }

    @Test
    void downWhenTheConnectionIsNotValid() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        assertThat(check.isUp()).isFalse();
        verify(connection).close();
    }

    @Test
    void downWhenNoConnectionCanBeObtained() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        assertThat(check.isUp()).isFalse();
    }

    @Test
    void downWhenThePoolCannotStart() throws SQLException {
        // Hikari throws an unchecked PoolInitializationException when the first connection fails.
        when(dataSource.getConnection()).thenThrow(new IllegalStateException("Failed to initialize pool"));

        assertThat(check.isUp()).isFalse();
    }
}
