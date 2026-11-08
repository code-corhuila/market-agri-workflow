package co.edu.corhuila.marketagri.workflow.app;

import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcDatabaseCheck;
import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place that knows the concrete types: use cases of the core are wired to the
 * outbound adapters here, with {@code @Bean} methods.
 */
@Configuration
public class WorkflowConfiguration {

    /** E-61: workflow answers only with its database up ({@code broker} joins with the event consumer). */
    @Bean
    ReadinessCheck databaseCheck(DataSource dataSource) {
        return new JdbcDatabaseCheck(dataSource);
    }
}
