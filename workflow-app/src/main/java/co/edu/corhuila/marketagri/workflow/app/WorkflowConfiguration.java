package co.edu.corhuila.marketagri.workflow.app;

import co.edu.corhuila.marketagri.workflow.adapter.in.http.PublicKeys;
import co.edu.corhuila.marketagri.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.marketagri.workflow.adapter.out.http.HttpConversationReader;
import co.edu.corhuila.marketagri.workflow.adapter.out.http.HttpPaymentCreator;
import co.edu.corhuila.marketagri.workflow.adapter.out.http.HttpStockReservations;
import co.edu.corhuila.marketagri.workflow.adapter.out.http.ServiceCaller;
import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcCheckoutSagas;
import co.edu.corhuila.marketagri.workflow.adapter.out.persistence.JdbcDatabaseCheck;
import co.edu.corhuila.marketagri.workflow.application.port.in.StartCheckout;
import co.edu.corhuila.marketagri.workflow.application.port.out.CheckoutSagas;
import co.edu.corhuila.marketagri.workflow.application.port.out.ConversationReader;
import co.edu.corhuila.marketagri.workflow.application.port.out.PaymentCreator;
import co.edu.corhuila.marketagri.workflow.application.port.out.ReadinessCheck;
import co.edu.corhuila.marketagri.workflow.application.port.out.StockReservations;
import co.edu.corhuila.marketagri.workflow.application.usecase.CheckoutSteps;
import co.edu.corhuila.marketagri.workflow.application.usecase.StartCheckoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only place that knows the concrete types: use cases of the core are wired to the
 * outbound adapters here, with {@code @Bean} methods.
 */
@Configuration
public class WorkflowConfiguration {

    /**
     * Validator of the tokens of auth (ADR-020): the issuer and the public key by configuration,
     * JWT_PUBLIC_KEY first, JWT_PUBLIC_KEY_FILE otherwise. Without a key the service does not start.
     */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${jwt.issuer}") String issuer,
                                @Value("${jwt.public-key:}") String publicKey,
                                @Value("${jwt.public-key-file:}") String publicKeyFile) {
        RSAPublicKey key;
        if (!publicKey.isBlank()) {
            key = PublicKeys.fromPem(publicKey);
        } else if (!publicKeyFile.isBlank()) {
            key = PublicKeys.fromFile(Path.of(publicKeyFile));
        } else {
            throw new IllegalStateException("Set JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE");
        }
        return new Rs256Verifier(key, issuer, Clock.systemUTC());
    }

    /** E-61: workflow answers only with its database up ({@code broker} joins with the event consumer). */
    @Bean
    ReadinessCheck databaseCheck(DataSource dataSource) {
        return new JdbcDatabaseCheck(dataSource);
    }

    /** The saga store (ADR-013): {@code saga_instance} and {@code saga_step}, as workflow_app. */
    @Bean
    JdbcCheckoutSagas checkoutSagas(JdbcClient jdbc, TransactionTemplate transaction, ObjectMapper json) {
        return new JdbcCheckoutSagas(jdbc, transaction, json);
    }

    /**
     * Every call to a participant (Anexo D, ADR-015 §4): the workflow's SERVICE_TOKEN, a timeout per
     * attempt, at most HTTP_ATTEMPTS tries with full jitter. Without a token the service does not start.
     */
    @Bean
    ServiceCaller serviceCaller(@Value("${participants.service-token}") String serviceToken,
                                @Value("${participants.http.timeout}") Duration timeout,
                                @Value("${participants.http.attempts}") int attempts,
                                @Value("${participants.http.backoff-base}") Duration backoffBase) {
        return new ServiceCaller(serviceToken, timeout, attempts, backoffBase, RandomGenerator.getDefault(),
                Thread::sleep);
    }

    @Bean
    HttpConversationReader conversationReader(ServiceCaller caller, ObjectMapper json,
                                              @Value("${participants.chat-api-url}") URI chatApiUrl) {
        return new HttpConversationReader(caller, chatApiUrl, json);
    }

    @Bean
    HttpStockReservations stockReservations(ServiceCaller caller, ObjectMapper json,
                                            @Value("${participants.catalog-api-url}") URI catalogApiUrl) {
        return new HttpStockReservations(caller, catalogApiUrl, json);
    }

    @Bean
    HttpPaymentCreator paymentCreator(ServiceCaller caller, ObjectMapper json,
                                      @Value("${participants.transactions-api-url}") URI transactionsApiUrl) {
        return new HttpPaymentCreator(caller, transactionsApiUrl, json);
    }

    /** E-40: the saga of ADR-015 over the ports; the tests replace any of them with a {@code @Primary} one. */
    @Bean
    StartCheckout startCheckout(ConversationReader conversations, StockReservations reservations,
                                PaymentCreator payments, CheckoutSagas sagas) {
        Clock clock = Clock.systemUTC();
        return new StartCheckoutService(sagas, new CheckoutSteps(conversations, reservations, payments, sagas, clock),
                clock);
    }
}
