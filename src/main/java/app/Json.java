package app;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Читает плоский JSON-объект. Вложенные значения заменяются на {@link #NESTED} и не хранятся. */
final class Json {
    /** Заглушка для объекта, массива и null: вызывающему они не нужны. */
    static final Object NESTED = new Object();
    private final byte[] in;
    private int i;

    private Json(byte[] in) {
        this.in = in;
    }

    /** Верхний уровень обязан быть объектом, и после него не должно остаться байт. */
    static Map<String, Object> object(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException();
        }
        Json json = new Json(data);
        json.skipWs();
        Map<String, Object> map = json.parseObject();
        json.skipWs();
        if (json.i != data.length) {
            throw new IllegalArgumentException();
        }
        return map;
    }

    /** Пары ключ-значение до закрывающей скобки. Пустой объект допустим. */
    private Map<String, Object> parseObject() {
        expect((byte) '{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (eat((byte) '}')) {
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect((byte) ':');
            skipWs();
            map.put(key, parseValue());
            skipWs();
            if (eat((byte) '}')) {
                return map;
            }
            expect((byte) ',');
        }
    }

    /** Строка, число, true, false. Объект, массив и null не сохраняются. */
    private Object parseValue() {
        if (i >= in.length) {
            throw new IllegalArgumentException();
        }
        return switch (in[i]) {
            case '"' -> parseString();
            case '{' -> { parseObject(); yield NESTED; }
            case '[' -> { parseArray(); yield NESTED; }
            case 't' -> { literal("true"); yield Boolean.TRUE; }
            case 'f' -> { literal("false"); yield Boolean.FALSE; }
            case 'n' -> { literal("null"); yield NESTED; }
            default -> parseNumber();
        };
    }

    /** Проходит массив, чтобы не сбиться с позиции, и ничего не возвращает. */
    private void parseArray() {
        expect((byte) '[');
        skipWs();
        if (eat((byte) ']')) {
            return;
        }
        while (true) {
            skipWs();
            parseValue();
            skipWs();
            if (eat((byte) ']')) {
                return;
            }
            expect((byte) ',');
        }
    }

    /** Строка в кавычках: экранирование JSON и многобайтовый UTF-8. */
    private String parseString() {
        expect((byte) '"');
        StringBuilder out = new StringBuilder();
        while (i < in.length) {
            int b = in[i] & 0xff;
            if (b == '"') {
                i++;
                return out.toString();
            }
            if (b == '\\') {
                i++;
                if (i >= in.length) {
                    throw new IllegalArgumentException();
                }
                int e = in[i++] & 0xff;
                out.append(switch (e) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> hex4();
                    default -> throw new IllegalArgumentException();
                });
                continue;
            }
            if (b < 0x20) {
                throw new IllegalArgumentException();
            }
            if (b < 0x80) {
                i++;
                out.append((char) b);
                continue;
            }
            int need = b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
            if ((b & 0xC0) == 0x80 || i + need > in.length) {
                throw new IllegalArgumentException();
            }
            out.append(new String(in, i, need, StandardCharsets.UTF_8));
            i += need;
        }
        throw new IllegalArgumentException();
    }

    /** Четыре шестнадцатеричные цифры после \\u. */
    private char hex4() {
        if (i + 4 > in.length) {
            throw new IllegalArgumentException();
        }
        int n = 0;
        for (int k = 0; k < 4; k++) {
            int h = Character.digit(in[i++] & 0xff, 16);
            if (h < 0) {
                throw new IllegalArgumentException();
            }
            n = (n << 4) | h;
        }
        return (char) n;
    }

    /** Целое становится Long, дробное и экспонента — Double. */
    private Number parseNumber() {
        int start = i;
        eat((byte) '-');
        if (i >= in.length || !digit(in[i])) {
            throw new IllegalArgumentException();
        }
        if (in[i] == '0') {
            i++;
        } else {
            while (i < in.length && digit(in[i])) {
                i++;
            }
        }
        boolean real = false;
        if (i < in.length && in[i] == '.') {
            real = true;
            i++;
            if (i >= in.length || !digit(in[i])) {
                throw new IllegalArgumentException();
            }
            while (i < in.length && digit(in[i])) {
                i++;
            }
        }
        if (i < in.length && (in[i] == 'e' || in[i] == 'E')) {
            real = true;
            i++;
            if (i < in.length && (in[i] == '+' || in[i] == '-')) {
                i++;
            }
            if (i >= in.length || !digit(in[i])) {
                throw new IllegalArgumentException();
            }
            while (i < in.length && digit(in[i])) {
                i++;
            }
        }
        String text = new String(in, start, i - start, StandardCharsets.US_ASCII);
        if (!real) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return Double.parseDouble(text);
            }
        }
        return Double.parseDouble(text);
    }

    /** Сверяет true, false или null байт в байт. */
    private void literal(String word) {
        for (int k = 0; k < word.length(); k++) {
            if (i >= in.length || in[i++] != (byte) word.charAt(k)) {
                throw new IllegalArgumentException();
            }
        }
    }

    /** Пропускает пробел, перевод строки, возврат каретки и табуляцию. */
    private void skipWs() {
        while (i < in.length) {
            byte c = in[i];
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            i++;
        }
    }

    /** Требует следующий байт. Иначе JSON считается битым. */
    private void expect(byte c) {
        if (!eat(c)) {
            throw new IllegalArgumentException();
        }
    }

    /** Съедает байт, если он совпал. */
    private boolean eat(byte c) {
        if (i < in.length && in[i] == c) {
            i++;
            return true;
        }
        return false;
    }

    /** Десятичная цифра. */
    private static boolean digit(byte c) {
        return c >= '0' && c <= '9';
    }
}
