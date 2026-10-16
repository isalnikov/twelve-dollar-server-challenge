package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Обрезка пробелов JavaScript, обязательное body и предел 500 символов. */
class BodiesTest {

    @Test
    /** Крайние пробелы JavaScript снимаются, текст внутри остаётся. */
    void trimsJavascriptWhitespaceAndKeepsTheText() {
        Parsed body = Bodies.parse(json("  hello  ").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, body.status());
        assertEquals("hello", body.text());
    }

    @Test
    /** Битый JSON — malformed JSON body. */
    void rejectsMalformedJson() {
        assertRejected("{bad json", 400, "malformed JSON body");
    }

    @Test
    /** Нет поля, пустая строка и не строка — body is required. */
    void rejectsAMissingOrBlankBody() {
        assertRejected("{}", 400, "body is required");
        assertRejected("{\"body\":123}", 400, "body is required");
        assertRejected("{\"body\":\"\"}", 400, "body is required");
        assertRejected("{\"body\":\"   \"}", 400, "body is required");
    }

    @Test
    /** 501 символ после обрезки не проходит. */
    void rejectsABodyLongerThan500Characters() {
        assertRejected(json("a".repeat(501)), 400, "body must be at most 500 characters");
        Parsed ok = Bodies.parse(json("  " + "b".repeat(500) + " ").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ok.status());
        assertEquals(500, ok.text().length());
    }

    @Test
    /** Кавычки и не-ASCII остаются после обрезки. */
    void keepsQuotesAndNonAsciiAfterTrim() {
        Parsed body = Bodies.parse(json("  test: <b>&amp;</b> \"quoted\" / café ✓  ").getBytes(StandardCharsets.UTF_8));
        assertEquals("test: <b>&amp;</b> \"quoted\" / café ✓", body.text());
    }

    private static void assertRejected(String json, int status, String error) {
        Parsed body = Bodies.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(status, body.status());
        assertEquals(error, body.error());
    }

    private static String json(String body) {
        return "{\"body\":" + quote(body) + "}";
    }

    private static String quote(String body) {
        return "\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
