package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The buyer of E-40 and E-46 is the UUID sub of a user token (authentication.md §2). */
class CallerTest {

    @Test
    void aUserTokenGivesItsUuid() {
        UUID id = UUID.randomUUID();

        assertThat(new Caller(id.toString(), "BUYER", "Ana").userId()).isEqualTo(id);
    }

    @Test
    void aServiceTokenIsNotAUser() {
        Caller workflow = new Caller("service:workflow", "SERVICE", null);

        assertThat(workflow.isService()).isTrue();
        assertThatThrownBy(workflow::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aUserTokenWhoseSubIsNotAUuidIsRejected() {
        assertThatThrownBy(() -> new Caller("ana", "BUYER", "Ana").userId())
                .isInstanceOf(UnauthorizedException.class);
    }
}
