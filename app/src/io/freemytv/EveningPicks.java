package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The evening picks' plain logic (no Android, tested on the JVM by tools/test_news.sh): reading the model's answer,
 * leaving out what was suggested or watched before, and when the day's picks are due.
 */
final class EveningPicks {
    static final long DAY = 24 * 3600_000L, KEEP = 90 * DAY;
    static final int BUILD_HOUR = 15;

    private EveningPicks() {}

    /** One suggestion; link and image are filled in once it's found on KINO.WATCH or YouTube. */
    static final class Item {
        String kind = "", title = "", year = "", query = "", why = "", link, image, app;

        boolean playable() {
            return link != null && !link.isEmpty();
        }
    }

    /** The picks in {"picks":[{kind,title,year,query,why}]}, wherever that object sits in the text; empty if none. */
    static List<Item> parse(String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = closing(text, start);
            if (end < 0) break;
            Object v = new Reader(text.substring(start, end + 1)).value();
            if (v instanceof Map && ((Map<?, ?>) v).get("picks") instanceof List) {
                for (Object o : (List<?>) ((Map<?, ?>) v).get("picks")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    Item i = new Item();
                    i.kind = str(m.get("kind"));
                    i.title = str(m.get("title"));
                    i.year = str(m.get("year"));
                    i.query = str(m.get("query"));
                    i.why = str(m.get("why"));
                    if (!i.title.isEmpty()) out.add(i);
                }
                return out;
            }
            start = text.indexOf('{', start + 1);
        }
        return out;
    }

    /** Leaves out what was suggested in the last 90 days and what was already watched (by title). */
    static List<Item> fresh(List<Item> in, Map<String, Long> history, Set<String> watched, long now) {
        List<Item> out = new ArrayList<>();
        for (Item i : in) {
            String k = key(i.title);
            Long at = history.get(k);
            if (at != null && now - at < KEEP) continue;
            if (watched.contains(k)) continue;
            out.add(i);
        }
        return out;
    }

    /** The first n that play, in their order. */
    static List<Item> take(List<Item> resolved, int n) {
        List<Item> out = new ArrayList<>();
        for (Item i : resolved) {
            if (out.size() >= n) break;
            if (i.playable()) out.add(i);
        }
        return out;
    }

    static void remember(Map<String, Long> history, List<Item> shown, long now) {
        for (Item i : shown) history.put(key(i.title), now);
    }

    static void prune(Map<String, Long> history, long now) {
        history.values().removeIf(at -> now - at > KEEP);
    }

    static final int MAX_TRIES = 3;
    static final long RETRY = 30 * 60_000L;

    /** Another try after failures today: at most 3 a day (each costs web searches), 30 min apart. */
    static boolean mayRetry(int failsToday, long failedAt, long now) {
        if (failsToday >= MAX_TRIES) return false;
        return failedAt <= 0 || now < failedAt || now - failedAt >= RETRY;
    }

    /** At or after 15:00 local time, and not built yet on today's date. */
    static boolean due(long lastBuilt, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        if (c.get(Calendar.HOUR_OF_DAY) < BUILD_HOUR) return false;
        if (lastBuilt <= 0) return true;
        Calendar b = Calendar.getInstance(tz);
        b.setTimeInMillis(lastBuilt);
        return b.get(Calendar.YEAR) != c.get(Calendar.YEAR) || b.get(Calendar.DAY_OF_YEAR) != c.get(Calendar.DAY_OF_YEAR);
    }

    /** A title as compared: lower case, ё as е, punctuation gone. */
    static String key(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double && ((Double) o) == Math.floor((Double) o)) return String.valueOf(((Double) o).longValue());
        return String.valueOf(o).trim();
    }

    /** Where the object opened at `open` closes, minding strings; -1 if it never does. */
    private static int closing(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** A small JSON reader: objects as maps, arrays as lists, numbers as doubles; null where the text isn't JSON. */
    private static final class Reader {
        private final String s;
        private int p;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            try {
                return read();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void space() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private Object read() {
            space();
            char c = s.charAt(p);
            if (c == '{') {
                Map<String, Object> m = new HashMap<>();
                p++;
                space();
                if (s.charAt(p) == '}') {
                    p++;
                    return m;
                }
                while (true) {
                    space();
                    String k = string();
                    space();
                    if (s.charAt(p++) != ':') throw new IllegalStateException();
                    m.put(k, read());
                    space();
                    char d = s.charAt(p++);
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalStateException();
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                p++;
                space();
                if (s.charAt(p) == ']') {
                    p++;
                    return l;
                }
                while (true) {
                    l.add(read());
                    space();
                    char d = s.charAt(p++);
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalStateException();
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", p)) {
                p += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", p)) {
                p += 4;
                return null;
            }
            int from = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            return Double.parseDouble(s.substring(from, p));
        }

        private String string() {
            if (s.charAt(p++) != '"') throw new IllegalStateException();
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(p++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                        break;
                    default: b.append(e);
                }
            }
        }
    }
}
