package io.freemytv;

import static io.freemytv.NewsTests.check;
import static io.freemytv.NewsTests.run;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NewsOrderTests {
    static final long DAY = 24 * 3600_000L, NOW = 1793109600000L;

    static NewsOrder.Story story(String id, String topic, int score) {
        NewsOrder.Story s = new NewsOrder.Story();
        s.id = id;
        s.topic = topic;
        s.score = score;
        return s;
    }

    static NewsFeeds.Item item(String id, long at) {
        NewsFeeds.Item i = new NewsFeeds.Item();
        i.id = id;
        i.at = at;
        return i;
    }

    static void all() {
        run("orderNoLongRuns", () -> {
            List<NewsOrder.Story> in = Arrays.asList(story("a", "AI", 90), story("b", "AI", 80), story("c", "AI", 70),
                    story("d", "AI", 60), story("e", "Music", 50), story("f", "Music", 40));
            List<NewsOrder.Story> out = NewsOrder.order(in);
            check(out.size() == 6, "nothing lost");
            check(out.get(0).id.equals("a"), "best first, got " + out.get(0).id);
            for (int i = 2; i < out.size(); i++) {
                boolean three = out.get(i).topic.equals("AI") && out.get(i - 1).topic.equals("AI") && out.get(i - 2).topic.equals("AI");
                check(!three, "three AI in a row at " + i);
            }
        });
        run("orderAllSameTopic", () -> {
            List<NewsOrder.Story> out = NewsOrder.order(Arrays.asList(story("x", "AI", 10), story("y", "AI", 30), story("z", "AI", 20)));
            check(out.size() == 3 && out.get(0).id.equals("y") && out.get(1).id.equals("z") && out.get(2).id.equals("x"), "by score");
        });
        run("capDailyRespectsToday", () -> {
            List<NewsOrder.Story> c = new ArrayList<>();
            for (int i = 0; i < 60; i++) c.add(story("s" + i, "T", i));
            List<NewsOrder.Story> kept = NewsOrder.capDaily(c, 10);
            check(kept.size() == 40, "keeps 40, got " + kept.size());
            int min = Integer.MAX_VALUE;
            for (NewsOrder.Story s : kept) min = Math.min(min, s.score);
            check(min == 20, "the best 40, lowest kept " + min);
            check(NewsOrder.capDaily(c, 55).isEmpty(), "none when over the cap");
        });
        run("freshestFilters", () -> {
            List<NewsFeeds.Item> items = new ArrayList<>();
            items.add(item("seen", NOW - 3600_000L));
            items.add(item("old", NOW - 37 * 3600_000L));
            items.add(item("older", NOW - 5 * 3600_000L));
            items.add(item("newer", NOW - 3600_000L));
            for (int i = 0; i < 200; i++) items.add(item("bulk" + i, NOW - 6 * 3600_000L - i * 1000L));
            Set<String> seen = new HashSet<>(Arrays.asList("seen"));
            List<NewsFeeds.Item> f = NewsOrder.freshest(items, seen, NOW);
            check(f.size() == 120, "at most 120, got " + f.size());
            check(f.get(0).id.equals("newer") && f.get(1).id.equals("older"), "newest first");
            for (NewsFeeds.Item i : f) check(!i.id.equals("seen") && !i.id.equals("old"), "filtered " + i.id);
        });
        run("validateStoryRejects", () -> {
            check(!NewsOrder.validateStory(Arrays.asList(7), 5, "t", "s"), "index out of range");
            check(!NewsOrder.validateStory(new ArrayList<Integer>(), 5, "t", "s"), "no index");
            check(!NewsOrder.validateStory(Arrays.asList(1), 5, "", "s"), "no title");
            check(!NewsOrder.validateStory(Arrays.asList(1), 5, "t", " "), "no summary");
            check(NewsOrder.validateStory(Arrays.asList(1, 9), 5, "t", "s"), "one good index is enough");
        });
        run("decay", () -> {
            double d = NewsOrder.decayed(1.0, NOW - 10 * DAY, NOW);
            check(Math.abs(d - Math.pow(0.97, 10)) < 1e-6, "decayed " + d);
        });
        run("topLikedOrder", () -> {
            Map<String, double[]> w = new HashMap<>();
            w.put("AI", new double[] {3, NOW - 30 * DAY});   // 3 * 0.97^30 ≈ 1.2
            w.put("VR", new double[] {2, NOW});
            w.put("Music", new double[] {1, NOW});
            w.put("Gone", new double[] {0.04, NOW});
            List<String> top = NewsOrder.topLiked(w, NOW, 2);
            check(top.equals(Arrays.asList("VR", "AI")), "top " + top);
            check(!NewsOrder.topLiked(w, NOW, 5).contains("Gone"), "tiny weights dropped");
        });
    }
}
