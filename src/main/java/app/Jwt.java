package app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Либо принятый пользователь, либо отказ с кодом и текстом ошибки. */
sealed interface Access {
    record Ok(long id, String username) implements Access {}
    record No(int status, String error) implements Access {}
}

/** Проверка JWT HS256 на каждом запросе. Кэша токенов нет. */
final class Jwt {
    private static final Access MISSING = new Access.No(401, "missing bearer token");
    private static final Access INVALID = new Access.No(401, "invalid or expired token");
    private static final Access PAYLOAD = new Access.No(401, "invalid token payload");

    private final Mac mac;
    private final LongSupplier nowSeconds;

    /** secret — сырые байты HMAC. nowSeconds отдаёт текущее время в секундах. */
    Jwt(byte[] secret, LongSupplier nowSeconds) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.nowSeconds = nowSeconds;
    }

    /** Заголовок Authorization. Без Bearer — missing, плохая подпись или срок — invalid. */
    Access authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return MISSING;
        }
        try {
            String token = header.substring(7);
            int dot1 = token.indexOf('.');
            int dot2 = dot1 < 0 ? -1 : token.indexOf('.', dot1 + 1);
            if (dot1 <= 0 || dot2 <= dot1 || token.indexOf('.', dot2 + 1) >= 0) {
                return INVALID;
            }
            String headerPart = token.substring(0, dot1);
            String payloadPart = token.substring(dot1 + 1, dot2);
            Map<String, Object> head = Json.object(b64(headerPart));
            if (!"HS256".equals(head.get("alg"))) {
                return INVALID;
            }
            byte[] actual = b64(token.substring(dot2 + 1));
            byte[] expected = mac.doFinal((headerPart + "." + payloadPart).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, actual)) {
                return INVALID;
            }
            Map<String, Object> payload = Json.object(b64(payloadPart));
            if (expired(payload.get("exp")) || expiredNotBefore(payload.get("nbf"))) {
                return INVALID;
            }
            if (!(payload.get("sub") instanceof String sub) || !(payload.get("username") instanceof String username)) {
                return PAYLOAD;
            }
            long id = Ids.parse(sub);
            if (id < 0) {
                return PAYLOAD;
            }
            return new Access.Ok(id, username);
        } catch (RuntimeException e) {
            return INVALID;
        }
    }

    /** exp задан и текущее время уже не меньше него. */
    private boolean expired(Object exp) {
        return exp != null && nowSeconds.getAsLong() >= number(exp);
    }

    /** nbf задан и текущее время ещё не дошло до него. */
    private boolean expiredNotBefore(Object nbf) {
        return nbf != null && nowSeconds.getAsLong() < number(nbf);
    }

    /** Число из JSON: целое или дробное. */
    private static double number(Object value) {
        if (value instanceof Long n) {
            return n.doubleValue();
        }
        if (value instanceof Double n) {
            return n;
        }
        throw new IllegalArgumentException();
    }

    /** Base64url без обязательного padding. */
    private static byte[] b64(String text) {
        int pad = (4 - text.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(text + "=".repeat(pad));
    }
}
