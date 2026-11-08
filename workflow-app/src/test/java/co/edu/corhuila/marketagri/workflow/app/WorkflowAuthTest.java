package co.edu.corhuila.marketagri.workflow.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Local validation of the token (Anexo C 5.3.7, authentication.md §3, ADR-020). The first eight
 * tests are the cases of ADR-020 point 7, with the same names in every validator (Java and Go).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowAuthTest extends WorkflowSpringTest {

    private static final String USER_ROUTE = "/probe/secured";
    private static final String ADMIN_ROUTE = "/probe/admin";
    private static final String INTERNAL_ROUTE = "/internal/v1/workflow/probe";

    @Autowired
    private MockMvc http;

    private ResultActions call(String route, String token) throws Exception {
        return http.perform(get(route).header("Authorization", "Bearer " + token));
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Debe iniciar sesión para continuar."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    // ── ADR-020 point 7 ──────────────────────────────────────────────────────────

    @Test
    void validToken() throws Exception {
        call(USER_ROUTE, TestTokens.user("PRODUCER").claim("sub", "5f0c1c8e-8b1d-4a57-9d0a-2b6f1e0c9a11").signed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("5f0c1c8e-8b1d-4a57-9d0a-2b6f1e0c9a11"))
                .andExpect(jsonPath("$.role").value("PRODUCER"));
    }

    @Test
    void expiredBeyondTheSkew() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").expiredSecondsAgo(31).signed()));
    }

    @Test
    void algNone() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").unsigned()));
    }

    @Test
    void hs256WithThePublicKeyAsSecret() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").hs256WithThePublicKeyAsSecret()));
    }

    @Test
    void noSub() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").without("sub").signed()));
    }

    @Test
    void wrongIssuer() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").claim("iss", "marketplace-auth").signed()));
    }

    @Test
    void signedWithAnotherKey() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").signedWithAnotherKey()));
    }

    @Test
    void serviceTokenOnAPublicRoute() throws Exception {
        call(USER_ROUTE, TestTokens.service().signed())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ── Rest of authentication.md §3 and Anexo C 5.3.12 ────────────────────────

    @Test
    void missingTokenIs401() throws Exception {
        expectUnauthorized(http.perform(get(USER_ROUTE)));
    }

    @Test
    void headerThatIsNotBearerIs401() throws Exception {
        expectUnauthorized(http.perform(get(USER_ROUTE).header("Authorization", "Basic YW5hOnNlY3JldA==")));
    }

    @Test
    void malformedTokenIs401() throws Exception {
        expectUnauthorized(call(USER_ROUTE, "not.a-token"));
    }

    @Test
    void expiredWithinTheSkewIsAccepted() throws Exception {
        call(USER_ROUTE, TestTokens.user("BUYER").expiredSecondsAgo(10).signed()).andExpect(status().isOk());
    }

    @Test
    void unknownRoleIs401() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("ROOT").signed()));
    }

    @Test
    void noExpIs401() throws Exception {
        expectUnauthorized(call(USER_ROUTE, TestTokens.user("BUYER").without("exp").signed()));
    }

    @Test
    void userTokenOnAnInternalRouteIs403() throws Exception {
        call(INTERNAL_ROUTE, TestTokens.user("ADMIN").signed())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void serviceTokenOnAnInternalRouteIsAccepted() throws Exception {
        call(INTERNAL_ROUTE, TestTokens.service().signed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("service:workflow"))
                .andExpect(jsonPath("$.role").value("SERVICE"));
    }

    @Test
    void anonymousRoutesIgnoreTokens() throws Exception {
        // api-contract §4: an expired token gets the same public answer as no token.
        call("/health", TestTokens.user("BUYER").expiredSecondsAgo(3600).signed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void requiredRoleAcceptsTheListedRole() throws Exception {
        call(ADMIN_ROUTE, TestTokens.user("ADMIN").signed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void requiredRoleRefusesAnotherRoleWith403() throws Exception {
        call(ADMIN_ROUTE, TestTokens.user("BUYER").signed())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
