package app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Подпись тестовых JWT тем же секретом, что у задания. */
final class Tokens {
    static final String SECRET = "twelve-dollar-challenge";

    private Tokens() {}

    /** Подписывает payload секретом задания. */
    static String sign(String payloadJson) {
        return sign(payloadJson, SECRET);
    }

    /** Собирает header.payload.signature. Чужой секрет нужен, чтобы проверить отказ. */
    static String sign(String payloadJson, String secret) {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64(payloadJson);
        String signingInput = header + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = b64(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Заголовок Authorization для пользователя. */
    static String bearer(long userId, String username, long exp) {
        String token = sign("{\"sub\":\"" + userId + "\",\"username\":\"" + username + "\",\"exp\":" + exp + "}");
        return "Bearer " + token;
    }

    /** Base64url без padding, как в JWT. */
    static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Байты подписи в Base64url без знака равенства. */
    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
