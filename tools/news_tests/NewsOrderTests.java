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
            List<NewsOrder.Story> kept = NewsOrder.capDaily(c, NewsOrder.PER_DAY - 40);
            check(kept.size() == 40, "keeps 40, got " + kept.size());
            int min = Integer.MAX_VALUE;
            for (NewsOrder.Story s : kept) min = Math.min(min, s.score);
            check(min == 20, "the best 40, lowest kept " + min);
            check(NewsOrder.capDaily(c, NewsOrder.PER_DAY + 5).isEmpty(), "none when over the cap");
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
        run("runFailsWhenModelNeverAnswered", () -> {
            check(!NewsOrder.runSucceeded(25, 0, 500, 2, 0), "every model call failed: a failure, retried in 10 min");
            check(!NewsOrder.runSucceeded(25, 25, 0, 0, 0), "every source failed");
            check(!NewsOrder.runSucceeded(25, 0, 0, 0, 0), "sources answered but nothing readable (a block page)");
            check(NewsOrder.runSucceeded(25, 3, 400, 2, 1), "one batch went through");
            check(NewsOrder.runSucceeded(25, 0, 400, 0, 0), "nothing new to send is fine");
        });
        run("staleStoriesDropped", () -> {
            NewsOrder.Story old = story("old", "AI", 99), fresh = story("fresh", "AI", 10), oldRead = story("oldRead", "AI", 50);
            old.at = NOW - 3 * DAY;
            oldRead.at = NOW - 3 * DAY;
            oldRead.read = true;
            fresh.at = NOW - 3600_000L;
            List<NewsOrder.Story> kept = NewsOrder.dropStale(Arrays.asList(old, fresh, oldRead), NOW);
            check(kept.size() == 1 && kept.get(0).id.equals("fresh"), "only stories under 48 h stay, got " + kept.size());
        });
        run("telegramBodyReachesModel", () -> {
            NewsFeeds.Item tg = item("t", NOW);
            tg.source = "Chan";
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 40; i++) body.append("word").append(i).append(' ');
            tg.text = body.toString().trim();
            tg.title = tg.text.substring(0, 140);
            String line = NewsOrder.promptLine(tg, 3, NOW);
            check(line.startsWith("[3] Chan, "), "head " + line);
            check(line.contains("word30"), "text past 140 characters is sent: " + line);
            check(!line.contains(" — "), "the title isn't repeated");
            NewsFeeds.Item rss = item("r", NOW);
            rss.source = "Feed";
            rss.title = "Headline";
            rss.text = "Body <<<sneaky>>> text";
            rss.image = "x";
            String l2 = NewsOrder.promptLine(rss, 0, NOW);
            check(l2.contains("photo") && l2.contains("Headline — Body sneaky text"), "rss line " + l2);
        });
        run("hasMoreOnlyWithMoreToTell", () -> {
            String summary = "Apple showed a new laptop. It is faster. Orders open on Friday.";
            check(!NewsOrder.hasMore(summary, Arrays.asList("Chan: Apple showed a new laptop, it is faster."), "https://t.me/chan/5"),
                    "a short post already told in full");
            StringBuilder longPost = new StringBuilder("Chan: ");
            for (int i = 0; i < 60; i++) longPost.append("fact ").append(i).append(". ");
            check(NewsOrder.hasMore(summary, Arrays.asList(longPost.toString()), "https://t.me/chan/6"), "a long post has more");
            check(NewsOrder.hasMore(summary, Arrays.asList("Feed: short."), "https://example.com/story"), "an article page has more");
        });
        run("spokenCappedAtSentence", () -> {
            NewsOrder.Story s = story("x", "AI", 1);
            s.title = "Большая новость";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 30; i++) b.append("Предложение номер ").append(i).append(" про события. ");
            s.summary = b.toString().trim();
            String said = NewsOrder.spoken(s, 500);
            check(said.startsWith("Большая новость. "), "title first: " + said.substring(0, 20));
            check(said.length() <= 500 && said.length() > 300, "about 500: " + said.length());
            check(said.endsWith("."), "ends on a whole sentence: …" + said.substring(said.length() - 10));
            s.summary = "Коротко.";
            check(NewsOrder.spoken(s, 500).equals("Большая новость. Коротко."), "short stays whole");
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
