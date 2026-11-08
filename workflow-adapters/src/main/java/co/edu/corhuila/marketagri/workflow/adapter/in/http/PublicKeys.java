package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Reads the public key of auth as configuration gives it (Anexo C 5.3.7): the PEM
 * ({@code BEGIN PUBLIC KEY}) on one line with literal {@code \n}, as an env file keeps it, or a
 * file with the PEM.
 */
public final class PublicKeys {

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private PublicKeys() {
    }

    public static RSAPublicKey fromPem(String pem) {
        String base64 = pem.replace("\\n", "\n").replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | ClassCastException | GeneralSecurityException invalid) {
            throw new IllegalArgumentException("The JWT public key is not an RSA public key in PEM format", invalid);
        }
    }

    public static RSAPublicKey fromFile(Path file) {
        try {
            return fromPem(Files.readString(file));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("Cannot read the JWT public key file " + file, unreadable);
        }
    }
}
