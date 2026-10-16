package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/** Подпись HS256, срок, алгоритм и поля sub/username. Часы в тесте заморожены. */
class JwtTest {
    private static final long NOW = 1_700_000_000L;

    private final Jwt jwt = new Jwt(Tokens.SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), () -> NOW);

    @Test
    /** Верный токен отдаёт id и имя. */
    void acceptsASignedTokenAndReadsTheUser() {
        Access access = jwt.authenticate(Tokens.bearer(7, "ada", NOW + 60));
        Access.Ok ok = assertInstanceOf(Access.Ok.class, access);
        assertEquals(7, ok.id());
        assertEquals("ada", ok.username());
    }

    @Test
    /** Нет заголовка или это не Bearer. */
    void rejectsAMissingBearer() {
        assertDenied(null, 401, "missing bearer token");
        assertDenied("Basic abc", 401, "missing bearer token");
    }

    @Test
    /** Чужая подпись, истёкший срок и alg не HS256 — одна и та же ошибка. */
    void rejectsABadSignatureExpiredTokenOrAlgNone() {
        String token = Tokens.sign("{\"sub\":\"1\",\"username\":\"ada\",\"exp\":" + (NOW + 60) + "}");
        assertDenied("Bearer " + token + "x", 401, "invalid or expired token");
        assertDenied(Tokens.bearer(1, "ada", NOW), 401, "invalid or expired token");
        String[] parts = token.split("\\.");
        String none = Tokens.b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + parts[1] + ".";
        assertDenied("Bearer " + none, 401, "invalid or expired token");
    }

    @Test
    /** sub не положительная строка или username не строка. */
    void rejectsAPayloadThatIsNotAUser() {
        assertDenied(header("{\"sub\":\"abc\",\"username\":\"ada\",\"exp\":" + (NOW + 60) + "}"), 401, "invalid token payload");
        assertDenied(header("{\"sub\":\"1\",\"exp\":" + (NOW + 60) + "}"), 401, "invalid token payload");
        assertDenied(header("{\"sub\":\"0\",\"username\":\"ada\",\"exp\":" + (NOW + 60) + "}"), 401, "invalid token payload");
    }

    private void assertDenied(String header, int status, String error) {
        Access.No no = assertInstanceOf(Access.No.class, jwt.authenticate(header));
        assertEquals(status, no.status());
        assertEquals(error, no.error());
    }

    private static String header(String payload) {
        return "Bearer " + Tokens.sign(payload);
    }
}
