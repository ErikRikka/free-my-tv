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
import java.util.TimeZone;

final class EveningTests {
    static final long DAY = 24 * 3600_000L;
    static final TimeZone MSK = TimeZone.getTimeZone("Europe/Moscow");

    static EveningPicks.Item item(String title, String link) {
        EveningPicks.Item i = new EveningPicks.Item();
        i.title = title;
        i.link = link;
        return i;
    }

    /** A moment in Moscow time: 2026-11-<day> hh:mm. */
    static long msk(int day, int h, int m) {
        java.util.Calendar c = java.util.Calendar.getInstance(MSK);
        c.clear();
        c.set(2026, java.util.Calendar.NOVEMBER, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static void all() {
        run("parsesJsonInsideProse", () -> {
            List<EveningPicks.Item> l = EveningPicks.parse("Here you go:\n```json\n{\"picks\":[{\"kind\":\"movie\",\"title\":\"X\","
                    + "\"year\":2019,\"query\":\"X 2019\",\"why\":\"slow horror\"}]}\n```");
            check(l.size() == 1, "one item, got " + l.size());
            EveningPicks.Item i = l.get(0);
            check("X".equals(i.title) && "2019".equals(i.year) && "slow horror".equals(i.why), "fields " + i.title + "|" + i.year + "|" + i.why);
            check("movie".equals(i.kind) && "X 2019".equals(i.query), "kind/query " + i.kind + "|" + i.query);
            check(EveningPicks.parse("no json").isEmpty(), "no json");
            check(EveningPicks.parse(null).isEmpty(), "null");
        });
        run("parsesEscapesAndBracesInStrings", () -> {
            List<EveningPicks.Item> l = EveningPicks.parse("{\"picks\":[{\"title\":\"A \\\"quoted\\\" {brace}\",\"why\":\"line\\nnext\"},"
                    + "{\"title\":\"B\"}]} trailing } junk");
            check(l.size() == 2, "two items, got " + l.size());
            check("A \"quoted\" {brace}".equals(l.get(0).title), "title " + l.get(0).title);
            check("line\nnext".equals(l.get(0).why), "why " + l.get(0).why);
        });
        run("dropsAlreadySuggested", () -> {
            long now = msk(2, 16, 0);
            Map<String, Long> history = new HashMap<>();
            history.put(EveningPicks.key("Ёлки"), now - 10 * DAY);
            history.put(EveningPicks.key("Old"), now - 91 * DAY);
            Set<String> watched = new HashSet<>(Arrays.asList(EveningPicks.key("Seen")));
            List<EveningPicks.Item> in = Arrays.asList(item("Елки", null), item("Old", null), item("Seen!", null), item("New", null));
            List<EveningPicks.Item> out = EveningPicks.fresh(in, history, watched, now);
            check(out.size() == 2 && "Old".equals(out.get(0).title) && "New".equals(out.get(1).title), "kept " + titles(out));
        });
        run("keepsOnlyResolved", () -> {
            List<EveningPicks.Item> in = Arrays.asList(item("a", "x"), item("b", null), item("c", "x"), item("d", null), item("e", "x"));
            List<EveningPicks.Item> out = EveningPicks.take(in, 4);
            check(titles(out).equals("a,c,e"), "took " + titles(out));
            check(EveningPicks.take(in, 2).size() == 2, "capped at n");
        });
        run("prunesAfter90Days", () -> {
            long now = msk(2, 16, 0);
            Map<String, Long> h = new HashMap<>();
            h.put("old", now - 91 * DAY);
            h.put("recent", now - 89 * DAY);
            EveningPicks.prune(h, now);
            check(!h.containsKey("old") && h.containsKey("recent"), "left " + h.keySet());
            EveningPicks.remember(h, Arrays.asList(item("Новое", "x")), now);
            check(h.get(EveningPicks.key("Новое")) == now, "remembered");
        });
        run("retriesCappedPerDay", () -> {
            long now = msk(2, 18, 0), half = 30 * 60_000L;
            check(EveningPicks.mayRetry(0, 0, now), "no failure yet");
            check(!EveningPicks.mayRetry(1, now - half + 1, now), "within 30 min of a failure");
            check(EveningPicks.mayRetry(2, now - half, now), "30 min after the 2nd failure");
            check(!EveningPicks.mayRetry(3, now - 5 * half, now), "3 failures today: done until tomorrow");
        });
        run("dueOncePerDayAfter15", () -> {
            long yesterday = msk(1, 16, 0), today = msk(2, 15, 5);
            check(!EveningPicks.due(yesterday, msk(2, 14, 59), MSK), "before 15:00");
            check(EveningPicks.due(yesterday, msk(2, 15, 0), MSK), "at 15:00");
            check(!EveningPicks.due(today, msk(2, 22, 0), MSK), "already built today");
            check(!EveningPicks.due(today, msk(3, 1, 0), MSK), "after midnight, before 15:00");
            check(EveningPicks.due(today, msk(3, 15, 0), MSK), "next day at 15:00");
            check(EveningPicks.due(0, msk(2, 15, 30), MSK), "never built");
        });
    }

    static String titles(List<EveningPicks.Item> l) {
        List<String> t = new ArrayList<>();
        for (EveningPicks.Item i : l) t.add(i.title);
        return String.join(",", t);
    }
}
