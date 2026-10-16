package app;

import java.util.Map;

/** Результат разбора тела поста: status 0 — текст принят, иначе код и текст ошибки. */
record Parsed(int status, String error, String text) {}

/** Проверка JSON тела POST /posts: поле body, обрезка пробелов, длина 1–500. */
final class Bodies {
    private Bodies() {}

    /** Возвращает обрезанный текст или точную ошибку контракта. */
    static Parsed parse(byte[] json) {
        try {
            Map<String, Object> object = Json.object(json);
            Object body = object.get("body");
            if (!(body instanceof String text)) {
                return required();
            }
            text = trim(text);
            if (text.isEmpty()) {
                return required();
            }
            if (text.length() > 500) {
                return new Parsed(400, "body must be at most 500 characters", null);
            }
            return new Parsed(0, null, text);
        } catch (RuntimeException e) {
            return new Parsed(400, "malformed JSON body", null);
        }
    }

    /** Ошибка, когда строкового body нет или после обрезки он пуст. */
    private static Parsed required() {
        return new Parsed(400, "body is required", null);
    }

    /** Срезает пробелы JavaScript с обоих концов, не String.strip. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && space(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    /** Набор пробельных символов, который trim() в JavaScript считает пустым. */
    private static boolean space(int cp) {
        return switch (cp) {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680,
                    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
                    0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true;
            default -> false;
        };
    }
}
