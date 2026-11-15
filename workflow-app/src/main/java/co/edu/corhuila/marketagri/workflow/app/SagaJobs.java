package co.edu.corhuila.marketagri.workflow.app;

import co.edu.corhuila.marketagri.workflow.adapter.correlation.Correlation;
import co.edu.corhuila.marketagri.workflow.adapter.in.messaging.RabbitPaymentEvents;
import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcCheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.usecase.CheckoutSteps;
import co.edu.corhuila.marketagri.workflow.domain.model.SagaStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
class SagaJobs {
    @Bean
    @ConditionalOnProperty("workflow.broker.enabled")
    RabbitPaymentEvents paymentEvents(JdbcCheckoutSagas sagas, CheckoutSteps steps, ObjectMapper json,
                                     @Value("${workflow.broker.host}") String host,
                                     @Value("${workflow.broker.port}") int port,
                                     @Value("${workflow.broker.username}") String username,
                                     @Value("${workflow.broker.password}") String password) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(3000);
        factory.setHandshakeTimeout(3000);
        return new RabbitPaymentEvents(factory, json, sagas, steps);
    }

    @Bean
    @ConditionalOnProperty(value = "workflow.recovery.enabled", matchIfMissing = true)
    Recovery recovery(JdbcCheckoutSagas sagas, CheckoutSteps steps) {
        return new Recovery(sagas, steps);
    }

    record Recovery(JdbcCheckoutSagas sagas, CheckoutSteps steps) {
        @Scheduled(fixedDelayString = "PT1M")
        public void recover() {
            for (var id : sagas.recoverable(Instant.now().minusSeconds(30))) {
                try (var ignored = MDC.putCloseable(Correlation.MDC_KEY, id.toString())) {
                    var saga = steps.runFrom(sagas.findById(id).orElseThrow());
                    if (saga.status() == SagaStatus.FAILED) {
                        LoggerFactory.getLogger(Recovery.class).error("Stock release exhausted for saga {}", id);
                    }
                } catch (RuntimeException error) {
                    LoggerFactory.getLogger(Recovery.class).error("Could not recover saga {}", id, error);
                }
            }
        }
    }
}
