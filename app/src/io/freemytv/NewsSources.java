package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/**
 * local-assets/news.txt: where the news comes from and what the owner cares about. Sections [sources] ("rss <url>
 * <label>" or "tg <channel> <label>"), [topics], [practical] and [boring], one entry a line, "#" for comments.
 */
final class NewsSources {
    static final class Source {
        final String kind, where, label;

        Source(String kind, String where, String label) {
            this.kind = kind;
            this.where = where;
            this.label = label;
        }
    }

    final List<Source> sources = new ArrayList<>();
    final List<String> topics = new ArrayList<>(), practical = new ArrayList<>(), boring = new ArrayList<>();

    static NewsSources parse(String text) {
        NewsSources n = new NewsSources();
        if (text == null) return n;
        String section = "";
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase();
                continue;
            }
            switch (section) {
                case "sources": {
                    String[] p = line.split("\\s+", 3);
                    if (p.length < 2 || !(p[0].equals("rss") || p[0].equals("tg"))) break;   // a bad line is skipped
                    n.sources.add(new Source(p[0], p[1], p.length > 2 ? p[2] : p[1]));
                    break;
                }
                case "topics":
                    n.topics.add(line);
                    break;
                case "practical":
                    n.practical.add(line);
                    break;
                case "boring":
                    n.boring.add(line);
                    break;
                default:
                    break;
            }
        }
        return n;
    }

    boolean usable() {
        return !sources.isEmpty();
    }
}
