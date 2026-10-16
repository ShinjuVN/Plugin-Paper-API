package vn.smboss;

import java.util.*;

/**
 * SNBT parser/serializer toi gian.
 * Compound = LinkedHashMap, List = ArrayList, moi gia tri con lai giu nguyen dang String tho.
 */
public final class Snbt {

    private static final Set<String> STRIP = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "Air", "FallDistance", "Fire", "OnGround",
            "PortalCooldown", "HurtTime", "HurtByTimestamp", "DeathTime", "Brain",
            "fall_flying", "sleeping_pos", "leash", "ticks_frozen", "LastHurtByMob",
            "LastHurtByMobTimestamp", "LastHurtByPlayer", "LastHurtByPlayerTime", "SleepingX",
            "SleepingY", "SleepingZ", "Paper.SpawnReason", "WorldUUIDLeast", "WorldUUIDMost");

    private Snbt() {}

    /** Lay doan {...} dau tien-cuoi cung trong chuoi (ke ca khi co tien to "X has the following entity data:"). */
    public static Map<String, Object> extractAndParse(String text) {
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b < a) throw new IllegalArgumentException("Khong tim thay NBT dang {...}");
        return parseCompound(text.substring(a, b + 1));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseCompound(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object o = p.value();
        p.ws();
        if (!(o instanceof Map) || p.i != text.length()) throw new IllegalArgumentException("NBT khong hop le");
        return (Map<String, Object>) o;
    }

    /** Bo cac truong khong can thiet (gom ca Passengers de quy). */
    @SuppressWarnings("unchecked")
    public static void filterEntity(Map<String, Object> m) {
        m.keySet().removeIf(k -> STRIP.contains(k) || k.startsWith("Paper.") || k.startsWith("Spigot."));
        Object pass = m.get("Passengers");
        if (pass instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map) filterEntity((Map<String, Object>) o);
        }
    }

    public static String unquote(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0);
            if ((f == '"' || f == '\'') && s.charAt(s.length() - 1) == f) {
                StringBuilder sb = new StringBuilder();
                for (int k = 1; k < s.length() - 1; k++) {
                    char c = s.charAt(k);
                    if (c == '\\' && k + 1 < s.length() - 1) c = s.charAt(++k);
                    sb.append(c);
                }
                return sb.toString();
            }
        }
        return s;
    }

    public static String toString(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        write(m, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder sb) {
        if (o instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(key(e.getKey())).append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof List<?> list) {
            sb.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) sb.append(',');
                write(list.get(k), sb);
            }
            sb.append(']');
        } else {
            sb.append((String) o);
        }
    }

    private static String key(String k) {
        if (!k.isEmpty() && k.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' || c == '.' || c == '+')) {
            return k;
        }
        return "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class Parser {
        final String s;
        int i = 0;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " (vi tri " + i + ")");
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw err("Can '" + c + "'");
            i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw err("NBT bi cut");
            char c = s.charAt(i);
            if (c == '{') return compound();
            if (c == '[') return list();
            return scalar();
        }

        Map<String, Object> compound() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = key();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("NBT bi cut");
                char c = s.charAt(i++);
                if (c == ',') { ws(); if (i < s.length() && s.charAt(i) == '}') { i++; return m; } continue; }
                if (c == '}') return m;
                throw err("Can ',' hoac '}'");
            }
        }

        Object list() {
            // mang co kieu: [I; ...] [B; ...] [L; ...]
            if (i + 2 < s.length() && "BIL".indexOf(s.charAt(i + 1)) >= 0 && s.charAt(i + 2) == ';') {
                int end = s.indexOf(']', i);
                if (end < 0) throw err("Mang khong dong");
                String raw = s.substring(i, end + 1);
                i = end + 1;
                return raw;
            }
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (i >= s.length()) throw err("NBT bi cut");
                char c = s.charAt(i++);
                if (c == ',') { ws(); if (i < s.length() && s.charAt(i) == ']') { i++; return l; } continue; }
                if (c == ']') return l;
                throw err("Can ',' hoac ']'");
            }
        }

        String key() {
            if (i >= s.length()) throw err("NBT bi cut");
            char c = s.charAt(i);
            if (c == '"' || c == '\'') return unquote(quoted());
            int st = i;
            while (i < s.length() && s.charAt(i) != ':' && !Character.isWhitespace(s.charAt(i))) i++;
            if (st == i) throw err("Key rong");
            return s.substring(st, i);
        }

        String quoted() {
            char q = s.charAt(i);
            int st = i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\') i++;
                else if (c == q) return s.substring(st, i);
            }
            throw err("Chuoi khong dong");
        }

        String scalar() {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') return quoted();
            int st = i;
            while (i < s.length() && ",}]".indexOf(s.charAt(i)) < 0) i++;
            String v = s.substring(st, i).trim();
            if (v.isEmpty()) throw err("Gia tri rong");
            return v;
        }
    }
}
