package io.freemytv;

import static io.freemytv.NewsTests.check;
import static io.freemytv.NewsTests.run;

final class NewsSourcesTests {
    static void all() {
        run("sourcesParse", () -> {
            NewsSources n = NewsSources.parse("# my news\n[sources]\nrss https://example.com/feed The Example\ntg rozetked Rozetked\nrss\n"
                    + "  \n[topics]\nГаджеты и Mac\n\n# a comment\nИгры на PS5\n[practical]\nСербия: визы\n[boring]\nОтчёты компаний\n");
            check(n.sources.size() == 2, "two sources, got " + n.sources.size());
            NewsSources.Source a = n.sources.get(0), b = n.sources.get(1);
            check(a.kind.equals("rss") && a.where.equals("https://example.com/feed") && a.label.equals("The Example"), "rss line");
            check(b.kind.equals("tg") && b.where.equals("rozetked") && b.label.equals("Rozetked"), "tg line");
            check(n.topics.size() == 2 && n.topics.get(1).equals("Игры на PS5"), "topics " + n.topics);
            check(n.practical.size() == 1 && n.boring.size() == 1, "practical and boring");
            check(n.usable(), "usable");
        });
        run("sourcesEmpty", () -> {
            check(!NewsSources.parse("").usable(), "empty file");
            check(!NewsSources.parse("[topics]\nИИ").usable(), "no sources");
            check(!NewsSources.parse(null).usable(), "no file");
        });
    }
}
