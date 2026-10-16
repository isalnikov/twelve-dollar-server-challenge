package app;

/** Разбор положительного целого из пути. Ноль и нецифры не являются идентификатором. */
final class Ids {
    private Ids() {}

    /** Десятичная строка не длиннее 18 символов. Иначе -1. */
    static long parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 18) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n == 0 ? -1 : n;
    }
}
