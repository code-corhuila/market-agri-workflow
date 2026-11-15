package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * Validates an access or service token by itself, with the public key of auth (Anexo C 5.3.7,
 * authentication.md §3, ADR-020). Closed list of algorithms: only {@code RS256} is accepted, so
 * {@code none} and {@code HS256} signed with the public key never pass. Standard library only.
 */
public final class Rs256Verifier {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final String ALGORITHM = "RS256";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final Set<String> ROLES = Set.of("PRODUCER", "BUYER", "ADMIN", Caller.SERVICE_ROLE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    private final RSAPublicKey publicKey;
    private final String issuer;
    private final Clock clock;

    public Rs256Verifier(RSAPublicKey publicKey, String issuer, Clock clock) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER is required");
        }
        this.issuer = issuer;
    }

    /** The caller of a valid token; {@link InvalidTokenException} with the failed check otherwise. */
    public Caller verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("not three parts");
        }
        if (!ALGORITHM.equals(decode(parts[0]).path("alg").asText(null))) {
            throw new InvalidTokenException("alg is not RS256");
        }
        if (!signatureMatches(parts[0] + "." + parts[1], parts[2])) {
            throw new InvalidTokenException("signature does not verify");
        }
        JsonNode claims = decode(parts[1]);
        if (!issuer.equals(text(claims, "iss"))) {
            throw new InvalidTokenException("wrong iss");
        }
        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new InvalidTokenException("no exp");
        }
        if (clock.instant().isAfter(Instant.ofEpochSecond(exp.asLong()).plus(CLOCK_SKEW))) {
            throw new InvalidTokenException("expired");
        }
        String subject = text(claims, "sub");
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("no sub");
        }
        String role = text(claims, "role");
        if (!ROLES.contains(role)) {
            throw new InvalidTokenException("unknown role");
        }
        return new Caller(subject, role, text(claims, "name"));
    }

    private boolean signatureMatches(String signedPart, String signature) {
        try {
            Signature rsa = Signature.getInstance(SIGNATURE_ALGORITHM);
            rsa.initVerify(publicKey);
            rsa.update(signedPart.getBytes(StandardCharsets.US_ASCII));
            return rsa.verify(BASE64URL.decode(signature));
        } catch (IllegalArgumentException | SignatureException malformed) {
            return false;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("RSA signature verification is unavailable", unavailable);
        }
    }

    private static JsonNode decode(String part) {
        try {
            JsonNode node = JSON.readTree(BASE64URL.decode(part));
            if (node == null || !node.isObject()) {
                throw new InvalidTokenException("part is not a JSON object");
            }
            return node;
        } catch (IllegalArgumentException | IOException malformed) {
            throw new InvalidTokenException("part is not base64url JSON");
        }
    }

    private static String text(JsonNode claims, String name) {
        JsonNode value = claims.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
