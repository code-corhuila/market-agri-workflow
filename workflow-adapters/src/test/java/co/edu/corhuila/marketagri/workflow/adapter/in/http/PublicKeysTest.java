package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The public key arrives by configuration (Anexo C 5.3.7): JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE. */
class PublicKeysTest {

    private static final KeyPair KEYS = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String multiLinePem() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(KEYS.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void readsThePemOnOneLineWithLiteralNewlines() {
        // As an env file keeps it, and as market-agri-infra/scripts/dev-keys.sh writes it.
        String oneLine = multiLinePem().replace("\n", "\\n");

        assertThat(PublicKeys.fromPem(oneLine).getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void readsAMultiLinePem() {
        assertThat(PublicKeys.fromPem(multiLinePem()).getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void readsThePemFromAFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("public.pem"), multiLinePem());

        assertThat(PublicKeys.fromFile(file).getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void refusesSomethingThatIsNotAnRsaPublicKey() {
        assertThatThrownBy(() -> PublicKeys.fromPem("not a key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
