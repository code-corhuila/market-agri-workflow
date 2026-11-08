package co.edu.corhuila.marketagri.workflow.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tokens for the tests, signed with keys generated while the tests run (ADR-020 point 7: test
 * keys are never versioned). Claims as authentication.md §2.
 */
final class TestTokens {

    static final String ISSUER = "market-agri-auth";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private TestTokens() {
    }

    /** The public key as JWT_PUBLIC_KEY carries it: the PEM on one line with literal \n. */
    static String publicKeyOnOneLine() {
        return "-----BEGIN PUBLIC KEY-----\\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\\n-----END PUBLIC KEY-----";
    }

    static Builder user(String role) {
        return new Builder().claim("sub", UUID.randomUUID().toString()).claim("role", role).claim("name", "Ana Ruiz");
    }

    static Builder service() {
        return new Builder().claim("sub", "service:workflow").claim("role", "SERVICE");
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(Map<String, Object> part) {
        try {
            return BASE64URL.encodeToString(JSON.writeValueAsBytes(part));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class Builder {

        private final Map<String, Object> claims = new LinkedHashMap<>();

        private Builder() {
            long now = Instant.now().getEpochSecond();
            claims.put("iss", ISSUER);
            claims.put("iat", now);
            claims.put("exp", now + 3600);
        }

        Builder claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        Builder without(String name) {
            claims.remove(name);
            return this;
        }

        Builder expiredSecondsAgo(long seconds) {
            return claim("exp", Instant.now().getEpochSecond() - seconds);
        }

        String signed() {
            return rs256(KEYS.getPrivate());
        }

        String signedWithAnotherKey() {
            return rs256(OTHER_KEYS.getPrivate());
        }

        /** {@code alg: none}, empty signature. */
        String unsigned() {
            return input("none") + ".";
        }

        /** The classic attack: HS256 with the public key as the HMAC secret. */
        String hs256WithThePublicKeyAsSecret() {
            try {
                String input = input("HS256");
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(publicKeyOnOneLine().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
                return input + "." + BASE64URL.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private String rs256(PrivateKey key) {
            try {
                String input = input("RS256");
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(key);
                signature.update(input.getBytes(StandardCharsets.US_ASCII));
                return input + "." + BASE64URL.encodeToString(signature.sign());
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private String input(String algorithm) {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", algorithm);
            header.put("typ", "JWT");
            return encode(header) + "." + encode(claims);
        }
    }
}
