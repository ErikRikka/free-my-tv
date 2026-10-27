package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The news feed's rules with no Android in them: order, daily cap, freshness, likes, the model's answer checked. */
final class NewsOrder {
    private NewsOrder() {}

    static final int PER_DAY = 50, MAX_BATCH_INPUT = 120, MAX_RUN = 2;
    static final long FRESH = 36 * 3600_000L;
    private static final long DAY = 24 * 3600_000L;

    static final class Story {
        String id, title, summary, topic, flag, image, link, source, details;
        int score;
        long at;
        boolean read, liked;
        List<String> itemIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();   // the sources' own words, for the long retelling on OK
    }

    /** Best first, but no topic more than twice in a row while another topic is still left. */
    static List<Story> order(List<Story> unread) {
        List<Story> left = new ArrayList<>(unread);
        Collections.sort(left, (a, b) -> Integer.compare(b.score, a.score));
        List<Story> out = new ArrayList<>();
        while (!left.isEmpty()) {
            int pick = 0;
            if (out.size() >= MAX_RUN) {
                String t = out.get(out.size() - 1).topic;
                boolean run = true;
                for (int i = 1; i <= MAX_RUN; i++) run &= eq(out.get(out.size() - i).topic, t);
                if (run) {
                    for (int i = 0; i < left.size(); i++) {
                        if (!eq(left.get(i).topic, t)) {
                            pick = i;
                            break;
                        }
                    }
                }
            }
            out.add(left.remove(pick));
        }
        return out;
    }

    /** The best ones that still fit into today's 50. */
    static List<Story> capDaily(List<Story> candidates, int addedToday) {
        int room = Math.max(0, PER_DAY - addedToday);
        List<Story> l = new ArrayList<>(candidates);
        Collections.sort(l, (a, b) -> Integer.compare(b.score, a.score));
        return new ArrayList<>(l.subList(0, Math.min(room, l.size())));
    }

    /** What's worth sending to the model: not seen yet, at most 36 hours old, newest first, at most 120. */
    static List<NewsFeeds.Item> freshest(List<NewsFeeds.Item> items, Set<String> seen, long now) {
        List<NewsFeeds.Item> l = new ArrayList<>();
        for (NewsFeeds.Item i : items) if (i.id != null && !seen.contains(i.id) && i.at > 0 && now - i.at <= FRESH) l.add(i);
        Collections.sort(l, (a, b) -> Long.compare(b.at, a.at));
        return new ArrayList<>(l.subList(0, Math.min(MAX_BATCH_INPUT, l.size())));
    }

    /** A story from the model is kept only with text and at least one record of the batch it was given. */
    static boolean validateStory(List<Integer> itemIndexes, int batchSize, String title, String summary) {
        if (title == null || title.trim().isEmpty() || summary == null || summary.trim().isEmpty()) return false;
        for (Integer i : itemIndexes) if (i != null && i >= 0 && i < batchSize) return true;
        return false;
    }

    /**
     * Whether a collection run worked, so the next one waits 2 h (else 10 min): some source answered with something
     * readable, and if there was anything to send, the model took at least one batch.
     */
    static boolean runSucceeded(int sources, int failedSources, int items, int batches, int okBatches) {
        if (sources == 0 || failedSources >= sources || items == 0) return false;
        return batches == 0 || okBatches > 0;
    }

    static final long KEEP = 48 * 3600_000L;

    /** Stories more than two days old go (after a week with the TV off, last week's news isn't new). */
    static List<Story> dropStale(List<Story> stories, long now) {
        List<Story> out = new ArrayList<>();
        for (Story s : stories) if (now - s.at <= KEEP) out.add(s);
        return out;
    }

    /** One record as the model reads it: "[i] Source, 27 Oct 14:00, photo: title — text", the strangers' fences taken out. */
    static String promptLine(NewsFeeds.Item it, int i, long now) {
        String title = clean(it.title), text = clean(it.text);
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        StringBuilder b = new StringBuilder();
        b.append('[').append(i).append("] ").append(it.source).append(", ")
                .append(new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(new java.util.Date(it.at)))
                .append(it.image != null ? ", photo" : "").append(": ");
        if (!text.isEmpty() && text.startsWith(title.replace("…", ""))) b.append(text);   // a Telegram post: its title is its first words
        else b.append(title).append(text.isEmpty() ? "" : " — " + text);
        return b.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("<<<", "").replace(">>>", "").replaceAll("\\s+", " ").trim();
    }

    static double decayed(double weight, long likedAt, long now) {
        return weight * Math.pow(0.97, Math.max(0, now - likedAt) / (double) DAY);
    }

    /** value = {weight, last liked at}; the n heaviest after decay, the faded ones left out. */
    static List<String> topLiked(Map<String, double[]> weights, long now, int n) {
        Map<String, Double> d = new HashMap<>();
        for (Map.Entry<String, double[]> e : weights.entrySet()) {
            double w = decayed(e.getValue()[0], (long) e.getValue()[1], now);
            if (w > 0.05) d.put(e.getKey(), w);
        }
        List<String> names = new ArrayList<>(d.keySet());
        Collections.sort(names, (a, b) -> Double.compare(d.get(b), d.get(a)));
        return new ArrayList<>(names.subList(0, Math.min(n, names.size())));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
