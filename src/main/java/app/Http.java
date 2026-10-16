package app;

import io.netty.buffer.ByteBuf;
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

/**
 * Разбор одного запроса по сырому массиву.
 * Буфер Netty не читается по байту: это медленнее плотного прохода по массиву.
 */
final class Http {
    private static final byte[] CONTENT_LENGTH = "content-length".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AUTHORIZATION = "authorization".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION = "connection".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLOSE = "close".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEEP_ALIVE = "keep-alive".getBytes(StandardCharsets.US_ASCII);

    private Http() {}

    /**
     * Сколько байт съедено.
     * 0 — запрос ещё не собран, -1 — заголовки битые или длиннее лимита.
     */
    static int parse(byte[] raw, int length, Incoming out) {
        return parse(raw, 0, length, out);
    }

    /** То же, но байты лежат в массиве со смещения. Сам массив не копируется. */
    static int parse(byte[] raw, int offset, int length, Incoming out) {
        int end = headerEnd(raw, offset, length);
        if (end < 0) {
            return length >= 8192 ? -1 : 0;
        }
        int absEnd = offset + end;
        int lineEnd = crlf(raw, offset, absEnd + 2);
        if (lineEnd <= offset) {
            return -1;
        }
        int sp1 = indexOf(raw, offset, lineEnd, (byte) ' ');
        int sp2 = sp1 < 0 ? -1 : indexOf(raw, sp1 + 1, lineEnd, (byte) ' ');
        if (sp1 <= offset || sp2 <= sp1 || !startsWithHttp1(raw, sp2 + 1, lineEnd)) {
            return -1;
        }
        // HTTP/1.1 по умолчанию держит соединение, HTTP/1.0 — нет.
        boolean keep = lineEnd - (sp2 + 1) >= 8 && raw[sp2 + 8] == '1';
        String authorization = null;
        int contentLength = 0;
        int cursor = lineEnd + 2;
        while (cursor < absEnd) {
            int next = crlf(raw, cursor, absEnd + 2);
            if (next < 0) {
                return -1;
            }
            int colon = indexOf(raw, cursor, next, (byte) ':');
            if (colon > cursor) {
                int valueFrom = colon + 1;
                int valueTo = next;
                while (valueFrom < valueTo && (raw[valueFrom] == ' ' || raw[valueFrom] == '\t')) {
                    valueFrom++;
                }
                while (valueTo > valueFrom && (raw[valueTo - 1] == ' ' || raw[valueTo - 1] == '\t')) {
                    valueTo--;
                }
                if (headerIs(raw, cursor, colon, CONTENT_LENGTH)) {
                    contentLength = digits(raw, valueFrom, valueTo);
                    if (contentLength < 0 || contentLength > 16_384) {
                        return -1;
                    }
                } else if (headerIs(raw, cursor, colon, AUTHORIZATION)) {
                    authorization = new String(raw, valueFrom, valueTo - valueFrom, StandardCharsets.ISO_8859_1);
                } else if (headerIs(raw, cursor, colon, CONNECTION)) {
                    if (regionEquals(raw, valueFrom, valueTo, CLOSE)) {
                        keep = false;
                    } else if (regionEquals(raw, valueFrom, valueTo, KEEP_ALIVE)) {
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
        out.method = new String(raw, offset, sp1 - offset, StandardCharsets.US_ASCII);
        out.target = new String(raw, sp1 + 1, sp2 - sp1 - 1, StandardCharsets.US_ASCII);
        out.authorization = authorization;
        out.keepAlive = keep;
        out.body = contentLength == 0 ? null : Arrays.copyOfRange(raw, absEnd + 4, offset + total);
        return total;
    }

    /**
     * Разбор с readerIndex, не сдвигая его.
     * Если буфер на куче, читается его массив. Прямой буфер один раз копирует вызывающий в свой scratch.
     */
    static int parse(ByteBuf buf, Incoming out) {
        if (buf.hasArray()) {
            return parse(buf.array(), buf.arrayOffset() + buf.readerIndex(), buf.readableBytes(), out);
        }
        int n = buf.readableBytes();
        byte[] raw = new byte[n];
        buf.getBytes(buf.readerIndex(), raw);
        return parse(raw, n, out);
    }

    /** Индекс пустой строки от начала читаемого куска. Смотрит не дальше 8192 байт. */
    private static int headerEnd(byte[] raw, int offset, int length) {
        int limit = Math.min(length, 8192);
        for (int i = 0; i + 3 < limit; i++) {
            int at = offset + i;
            if (raw[at] == '\r' && raw[at + 1] == '\n' && raw[at + 2] == '\r' && raw[at + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Версия начинается с HTTP/1. */
    private static boolean startsWithHttp1(byte[] raw, int from, int to) {
        return to - from >= 7
                && raw[from] == 'H'
                && raw[from + 1] == 'T'
                && raw[from + 2] == 'T'
                && raw[from + 3] == 'P'
                && raw[from + 4] == '/'
                && raw[from + 5] == '1'
                && raw[from + 6] == '.';
    }

    /** Имя заголовка сравнивается без новой строки, регистр не важен. */
    private static boolean headerIs(byte[] raw, int from, int to, byte[] lower) {
        if (to - from != lower.length) {
            return false;
        }
        for (int i = 0; i < lower.length; i++) {
            int c = raw[from + i];
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (c != lower[i]) {
                return false;
            }
        }
        return true;
    }

    /** Значение заголовка целиком, без учёта регистра. */
    private static boolean regionEquals(byte[] raw, int from, int to, byte[] lower) {
        return headerIs(raw, from, to, lower);
    }

    /** Десятичное число или -1, если в значении есть не цифра. */
    private static int digits(byte[] raw, int from, int to) {
        if (from >= to) {
            return -1;
        }
        int n = 0;
        for (int i = from; i < to; i++) {
            int c = raw[i];
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
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
