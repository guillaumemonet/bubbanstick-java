package bubba.extract;

import java.util.List;
import java.util.Map;

/**
 * Small JSON writer for the extracted metadata. Lists of numbers / strings stay on one line,
 * other short values too; the rest is indented, one element per line.
 */
final class JsonOut {

    private static final int SHORT = 100;

    private JsonOut() {
    }

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        value(b, v, "");
        return b.append('\n').toString();
    }

    private static void value(StringBuilder b, Object v, String indent) {
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            String c = compact(v);
            if (c.length() <= SHORT || scalars(v)) {
                b.append(c);
                return;
            }
        }
        if (v instanceof Map<?, ?> m) {
            String in = indent + "  ";
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(in);
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                value(b, e.getValue(), in);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            b.append(indent).append('}');
        } else if (v instanceof List<?> l) {
            String in = indent + "  ";
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(in);
                value(b, l.get(i), in);
                b.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            b.append(indent).append(']');
        } else {
            b.append(compact(v));
        }
    }

    private static boolean scalars(Object v) {
        if (!(v instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (o instanceof Map<?, ?> || o instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    private static String compact(Object v) {
        StringBuilder b = new StringBuilder();
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            string(b, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) {
                    b.append(", ");
                }
                string(b, String.valueOf(e.getKey()));
                b.append(": ").append(compact(e.getValue()));
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(compact(l.get(i)));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("not JSON: " + v.getClass());
        }
        return b.toString();
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
