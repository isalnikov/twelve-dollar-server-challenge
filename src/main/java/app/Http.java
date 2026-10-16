package app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Разобранный запрос HTTP/1.1. Тело есть только при Content-Length больше нуля. */
final class Incoming {
    String method;
    String target;
    String authorization;
    byte[] body;
    boolean keepAlive;
}

/** Разбор одного запроса из буфера. Кодек Netty не используется. */
final class Http {
    private Http() {}

    /**
     * Сколько байт съедено.
     * 0 — запрос ещё не собран, -1 — заголовки битые или длиннее лимита.
     */
    static int parse(byte[] raw, int length, Incoming out) {
        int end = headerEnd(raw, length);
        if (end < 0) {
            return length >= 8192 ? -1 : 0;
        }
        int lineEnd = crlf(raw, 0, end + 2);
        if (lineEnd <= 0) {
            return -1;
        }
        String[] parts = new String(raw, 0, lineEnd, StandardCharsets.US_ASCII).split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            return -1;
        }
        // HTTP/1.1 по умолчанию держит соединение, HTTP/1.0 — нет.
        boolean keep = "HTTP/1.1".equals(parts[2]);
        String authorization = null;
        int contentLength = 0;
        int cursor = lineEnd + 2;
        while (cursor < end) {
            int next = crlf(raw, cursor, end + 2);
            if (next < 0) {
                return -1;
            }
            int colon = indexOf(raw, cursor, next, (byte) ':');
            if (colon > cursor) {
                String name = new String(raw, cursor, colon - cursor, StandardCharsets.US_ASCII);
                String value = new String(raw, colon + 1, next - colon - 1, StandardCharsets.ISO_8859_1).trim();
                if (name.equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                    if (contentLength < 0 || contentLength > 16_384) {
                        return -1;
                    }
                } else if (name.equalsIgnoreCase("authorization")) {
                    authorization = value;
                } else if (name.equalsIgnoreCase("connection")) {
                    if (value.equalsIgnoreCase("close")) {
                        keep = false;
                    } else if (value.equalsIgnoreCase("keep-alive")) {
                        keep = true;
                    }
                }
            }
            cursor = next + 2;
        }
        int total = end + 4 + contentLength;
        if (length < total) {
            return 0;
        }
        out.method = parts[0];
        out.target = parts[1];
        out.authorization = authorization;
        out.keepAlive = keep;
        out.body = contentLength == 0 ? null : Arrays.copyOfRange(raw, end + 4, total);
        return total;
    }

    /** Индекс пустой строки между заголовками и телом. Смотрит не дальше 8192 байт. */
    private static int headerEnd(byte[] raw, int length) {
        int limit = Math.min(length, 8192);
        for (int i = 0; i + 3 < limit; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Первые CR LF в отрезке. */
    private static int crlf(byte[] raw, int from, int to) {
        for (int i = from; i + 1 < to; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Первое вхождение байта в отрезке. */
    private static int indexOf(byte[] raw, int from, int to, byte needle) {
        for (int i = from; i < to; i++) {
            if (raw[i] == needle) {
                return i;
            }
        }
        return -1;
    }
}
