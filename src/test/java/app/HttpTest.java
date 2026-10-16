package app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Разбор строки запроса, заголовков, тела и склейки двух запросов в одном буфере. */
class HttpTest {

    @Test
    /** Метод, путь, Authorization и тело читаются из одного буфера. */
    void readsMethodPathAuthorizationAndBody() {
        byte[] raw = request("POST /posts HTTP/1.1\r\nHost: localhost\r\nauthorization: Bearer abc\r\nContent-Length: 16\r\n\r\n{\"body\":\"hello\"}");
        Incoming in = new Incoming();
        assertEquals(raw.length, Http.parse(raw, raw.length, in));
        assertEquals("POST", in.method);
        assertEquals("/posts", in.target);
        assertEquals("Bearer abc", in.authorization);
        assertArrayEquals("{\"body\":\"hello\"}".getBytes(StandardCharsets.US_ASCII), in.body);
        assertTrue(in.keepAlive);
    }

    @Test
    /** Неполное тело возвращает 0 и не портит разбор. */
    void waitsUntilTheBodyHasArrived() {
        byte[] raw = request("""
                POST /posts HTTP/1.1\r
                Content-Length: 15\r
                \r
                {"body":""");
        Incoming in = new Incoming();
        assertEquals(0, Http.parse(raw, raw.length, in));
        assertNull(in.method);
    }

    @Test
    /** Два запроса в одном буфере режутся по длине, Connection: close снимает keep-alive. */
    void splitsAPipelineAndHonorsConnectionClose() {
        byte[] raw = request("""
                GET /feed HTTP/1.1\r
                Connection: close\r
                \r
                GET /health HTTP/1.0\r
                \r
                """);
        Incoming first = new Incoming();
        int used = Http.parse(raw, raw.length, first);
        assertTrue(used > 0);
        assertEquals("GET", first.method);
        assertEquals("/feed", first.target);
        assertFalse(first.keepAlive);
        assertNull(first.body);
        Incoming second = new Incoming();
        byte[] rest = java.util.Arrays.copyOfRange(raw, used, raw.length);
        assertEquals(rest.length, Http.parse(rest, rest.length, second));
        assertEquals("/health", second.target);
        assertFalse(second.keepAlive);
    }

    private static byte[] request(String text) {
        return text.replace("\n", "\r\n").replace("\r\r\n", "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
