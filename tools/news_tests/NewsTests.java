package io.freemytv;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Plain-JVM tests for the news logic that has no Android in it. Run by tools/test_news.sh. */
public final class NewsTests {
    static int passed;
    static String dir;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(dir, "fixtures", name)), StandardCharsets.UTF_8);
    }

    static void run(String name, Runnable r) {
        try {
            r.run();
            passed++;
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": " + t);
            System.exit(1);
        }
    }

    interface Body { void run() throws Exception; }

    static Runnable t(Body b) {
        return () -> {
            try {
                b.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        dir = args[0];
        feeds();
        for (String extra : new String[] {"io.freemytv.NewsOrderTests", "io.freemytv.NewsSourcesTests"}) {
            try {
                Class.forName(extra).getDeclaredMethod("all").invoke(null);
            } catch (ClassNotFoundException ignored) {
            } catch (java.lang.reflect.InvocationTargetException e) {
                System.out.println("FAIL " + extra + ": " + e.getCause());
                System.exit(1);
            }
        }
        System.out.println(passed + " passed");
    }

    static void feeds() {
        run("rssMediaContent", t(() -> {
            List<NewsFeeds.Item> l = NewsFeeds.parseRss(fixture("rss_media.xml"), "Test");
            check(l.size() == 1, "one item, got " + l.size());
            NewsFeeds.Item i = l.get(0);
            check(i.title.equals("New camera & lens announced"), "title " + i.title);
            check(i.link.equals("https://example.com/news/camera"), "link " + i.link);
            check("https://example.com/img/camera.jpg".equals(i.image), "image " + i.image);
            check(i.at == 1793109600000L, "at " + i.at);
            check(i.text.equals("A small camera with a big sensor."), "text " + i.text);
            check(i.source.equals("Test") && i.id != null && !i.id.isEmpty(), "source/id");
        }));
        run("rssEnclosureAndInlineImg", t(() -> {
            List<NewsFeeds.Item> l = NewsFeeds.parseRss(fixture("rss_enclosure.xml"), "S");
            check(l.size() == 2, "two items");
            check("https://example.com/img/tour.jpg".equals(l.get(0).image), "enclosure " + l.get(0).image);
            check("https://example.com/img/album.jpg".equals(l.get(1).image), "inline img " + l.get(1).image);
            check(l.get(1).text.equals("The band is back."), "tags stripped: " + l.get(1).text);
        }));
        run("atomEntry", t(() -> {
            List<NewsFeeds.Item> l = NewsFeeds.parseRss(fixture("atom.xml"), "A");
            check(l.size() == 1, "one entry");
            check(l.get(0).link.equals("https://example.com/laptop"), "href " + l.get(0).link);
            check(l.get(0).at == 1793106268000L, "updated " + l.get(0).at);
            check(l.get(0).text.equals("Faster and longer battery."), "summary " + l.get(0).text);
            check("https://example.com/img/laptop.jpg".equals(l.get(0).image), "img from content " + l.get(0).image);
        }));
        run("telegramPost", t(() -> {
            List<NewsFeeds.Item> l = NewsFeeds.parseTelegram(fixture("telegram.html"), "chan");
            check(l.size() == 2, "two posts, got " + l.size());
            NewsFeeds.Item a = l.get(0), b = l.get(1);
            check(a.text.equals("Lilac is blooming in October Send us photos"), "text " + a.text);
            check(a.link.equals("https://t.me/chan/123"), "link " + a.link);
            check(a.at == 1793107834000L, "at " + a.at);
            check("https://cdn4.telesco.pe/file/abc.jpg".equals(a.image), "photo " + a.image);
            check(b.image == null, "emoji is not a photo: " + b.image);
            check(b.text.equals("Average salary & prices"), "entities " + b.text);
        }));
        run("imageFilter", t(() -> {
            check(!NewsFeeds.imageAllowed("https://telegram.org/img/emoji/40/X.png"), "emoji");
            check(!NewsFeeds.imageAllowed("https://meduza.io/imgly/share/1/x"), "share card");
            check(!NewsFeeds.imageAllowed(null) && !NewsFeeds.imageAllowed(""), "blank");
            check(NewsFeeds.imageAllowed("https://example.com/a.jpg"), "plain jpg");
        }));
        run("encodeCyrillic", t(() -> {
            String u = NewsFeeds.encodeUrl("https://a.com/wp/сн.jpg?x=1");
            check(u.startsWith("https://a.com/wp/") && u.endsWith(".jpg?x=1"), "kept " + u);
            for (char ch : u.toCharArray()) check(ch < 128, "non-ascii left in " + u);
        }));
        run("invalidXmlStillRead", t(() -> {
            List<NewsFeeds.Item> l = NewsFeeds.parseRss(fixture("rss_invalid.xml"), "B");
            check(l.size() == 1, "lenient parse, got " + l.size());
            check(l.get(0).title.equals("Ghosts & ghouls return"), "title " + l.get(0).title);
            check("https://example.com/img/ghost.jpg".equals(l.get(0).image), "img " + l.get(0).image);
            check(l.get(0).at == 1793095200000L, "date " + l.get(0).at);
        }));
        run("brokenXml", t(() -> {
            check(NewsFeeds.parseRss("<rss><item><title>x", "s").isEmpty(), "broken gives empty");
            check(NewsFeeds.parseRss("", "s").isEmpty(), "empty gives empty");
        }));
        run("readCappedDeadline", t(() -> {
            java.io.InputStream slow = new java.io.InputStream() {   // a byte every 30 ms: never times out a single read
                @Override public int read() throws java.io.IOException {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                    return 'a';
                }

                @Override public int read(byte[] b, int off, int len) throws java.io.IOException {   // as a socket: what has come
                    int c = read();
                    b[off] = (byte) c;
                    return 1;
                }
            };
            long t0 = System.currentTimeMillis();
            boolean threw = false;
            try {
                NewsFeeds.readCapped(slow, 2 * 1024 * 1024, t0 + 300);
            } catch (java.io.IOException e) {
                threw = true;
            }
            check(threw, "past the deadline it gives up");
            check(System.currentTimeMillis() - t0 < 1500, "and soon after it");
        }));
        run("readCappedStops", t(() -> {
            byte[] big = new byte[3 * 1024 * 1024];
            java.util.Arrays.fill(big, (byte) 'a');
            String s = NewsFeeds.readCapped(new ByteArrayInputStream(big), 2 * 1024 * 1024);
            check(s.length() <= 2 * 1024 * 1024 && s.length() > 1024 * 1024, "capped at " + s.length());
        }));
    }
}
