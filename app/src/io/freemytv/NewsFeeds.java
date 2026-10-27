package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads the news sources: RSS and Atom feeds, and public Telegram channels' web pages (t.me/s/name). Plain Java (no
 * Android), so its tests run on a computer: tools/test_news.sh.
 */
final class NewsFeeds {
    private NewsFeeds() {}

    static final class Item {
        String id, source, title = "", text = "", link = "", image;
        long at;
    }

    /** RSS 2.0 or Atom; an unreadable feed gives an empty list, never an exception. */
    static List<Item> parseRss(String xml, String source) {
        List<Item> out = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) return out;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no outside entities
            } catch (Exception ignored) {
            }
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            org.w3c.dom.Document d = b.parse(new InputSource(new StringReader(xml.trim())));
            NodeList all = d.getElementsByTagName("*");
            for (int n = 0; n < all.getLength(); n++) {
                Element e = (Element) all.item(n);
                String tag = local(e);
                if (!tag.equals("item") && !tag.equals("entry")) continue;
                Item it = new Item();
                it.source = source;
                String html = "";
                StringBuilder bodies = new StringBuilder();   // every text field: the picture may be in the full story only
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (!(c instanceof Element)) continue;
                    Element ce = (Element) c;
                    String t = local(ce);
                    switch (t) {
                        case "title":
                            it.title = plain(ce.getTextContent());
                            break;
                        case "link":
                            String href = ce.getAttribute("href");
                            if (!href.isEmpty() && (it.link.isEmpty() || "alternate".equals(ce.getAttribute("rel")))) it.link = href.trim();
                            else if (href.isEmpty() && it.link.isEmpty()) it.link = ce.getTextContent().trim();
                            break;
                        case "pubDate":
                        case "published":
                        case "updated":
                        case "date":
                            if (it.at == 0 || t.equals("pubDate")) it.at = date(ce.getTextContent().trim());
                            break;
                        case "description":
                        case "summary":
                        case "encoded":
                        case "content":
                            if (t.equals("content") && !ce.getAttribute("url").isEmpty()) {
                                if (it.image == null && looksImage(ce)) it.image = ce.getAttribute("url");
                            } else {
                                String body = ce.getTextContent();
                                bodies.append(body).append(' ');
                                if (html.isEmpty() || t.equals("description") || t.equals("summary")) html = body;
                            }
                            break;
                        case "thumbnail":
                            if (it.image == null && !ce.getAttribute("url").isEmpty()) it.image = ce.getAttribute("url");
                            break;
                        case "enclosure":
                            if (it.image == null && ce.getAttribute("type").startsWith("image")) it.image = ce.getAttribute("url");
                            break;
                        default:
                            break;
                    }
                }
                finish(it, html, bodies.toString(), out);
            }
        } catch (Exception | StackOverflowError e) {
            return lenient(xml, source);
        }
        return out;
    }

    private static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)");

    private static void finish(Item it, String html, String bodies, List<Item> out) {
        if (it.image == null) {
            Matcher m = IMG.matcher(bodies.isEmpty() ? html : bodies);
            while (m.find()) {
                String u = unescape(m.group(1));
                if (imageAllowed(u)) {
                    it.image = u;
                    break;
                }
            }
        }
        it.text = plain(html);
        if (it.title.isEmpty() && it.text.isEmpty()) return;
        it.id = id(it.link.isEmpty() ? it.source + it.title : it.link);
        out.add(it);
    }

    private static final Pattern ITEM = Pattern.compile("<(item|entry)[\\s>](.*?)</\\1>", Pattern.DOTALL);

    /** Feeds that aren't well-formed XML (a bare "&", a stray tag): read item by item with patterns instead. */
    private static List<Item> lenient(String xml, String source) {
        List<Item> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        while (m.find()) {
            String b = m.group(2);
            Item it = new Item();
            it.source = source;
            it.title = plain(field(b, "title"));
            String link = field(b, "link");
            Matcher href = Pattern.compile("<link[^>]+href=[\"']([^\"']+)").matcher(b);
            it.link = !link.trim().isEmpty() ? link.trim() : href.find() ? href.group(1) : "";
            String when = field(b, "pubDate");
            if (when.isEmpty()) when = field(b, "updated");
            if (when.isEmpty()) when = field(b, "published");
            it.at = date(when.trim());
            Matcher media = Pattern.compile("<(?:media:content|media:thumbnail|enclosure)[^>]+url=[\"']([^\"']+)").matcher(b);
            if (media.find()) it.image = unescape(media.group(1));
            String html = field(b, "description");
            if (html.isEmpty()) html = field(b, "summary");
            finish(it, html, b, out);
        }
        return out;
    }

    /** A tag's content, CDATA unwrapped (or the raw markup, entity-escaped as feeds send it). */
    private static String field(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(block);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[")) return v.substring(9, v.endsWith("]]>") ? v.length() - 3 : v.length());
        return unescape(v);
    }

    private static final Pattern TG_BLOCK = Pattern.compile("tgme_widget_message_wrap(.*?)(?=tgme_widget_message_wrap|\\z)", Pattern.DOTALL);
    private static final Pattern TG_TEXT = Pattern.compile("tgme_widget_message_text[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern TG_POST = Pattern.compile("data-post=\"([^\"]+)\"");
    private static final Pattern TG_TIME = Pattern.compile("<time[^>]*datetime=\"([^\"]+)\"");
    private static final Pattern TG_PHOTO = Pattern.compile("background-image:url\\('([^']+)'\\)");

    /** A channel's web page (t.me/s/channel), its posts oldest first as on the page; text-less posts are skipped. */
    static List<Item> parseTelegram(String html, String channel) {
        List<Item> out = new ArrayList<>();
        if (html == null) return out;
        Matcher b = TG_BLOCK.matcher(html);
        while (b.find()) {
            String blk = b.group(1);
            Matcher tx = TG_TEXT.matcher(blk);
            if (!tx.find()) continue;
            Item it = new Item();
            it.source = channel;
            it.text = plain(tx.group(1).replaceAll("(?i)<br\\s*/?>", " "));
            it.title = it.text.length() > 140 ? it.text.substring(0, 140) : it.text;
            Matcher p = TG_POST.matcher(blk);
            it.link = p.find() ? "https://t.me/" + p.group(1) : "";
            Matcher t = TG_TIME.matcher(blk);
            if (t.find()) it.at = date(t.group(1));
            Matcher ph = TG_PHOTO.matcher(blk);
            while (ph.find()) {
                String u = ph.group(1).startsWith("//") ? "https:" + ph.group(1) : ph.group(1);
                if (imageAllowed(u)) {
                    it.image = u;
                    break;
                }
            }
            it.id = id(it.link.isEmpty() ? channel + it.text : it.link);
            out.add(it);
        }
        return out;
    }

    /** A real photo: not an emoji, not a share card that is a picture of text, not blank. */
    static boolean imageAllowed(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        return !url.contains("telegram.org/img/emoji/") && !url.contains("/imgly/share/");
    }

    /** The address with any non-ASCII character in it percent-encoded (some sites put Thai or Cyrillic in file names). */
    static String encodeUrl(String url) {
        StringBuilder sb = new StringBuilder();
        for (char ch : url.toCharArray()) {
            if (ch < 128) {
                sb.append(ch);
                continue;
            }
            for (byte by : String.valueOf(ch).getBytes(StandardCharsets.UTF_8)) sb.append(String.format("%%%02X", by & 0xFF));
        }
        return sb.toString();
    }

    /** The response as UTF-8 text, cut at maxBytes (a source that sends a huge page doesn't fill the memory). */
    static String readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while (out.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()))) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String local(Element e) {
        String n = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    private static boolean looksImage(Element e) {
        String type = e.getAttribute("type"), medium = e.getAttribute("medium"), url = e.getAttribute("url");
        return type.startsWith("image") || medium.equals("image") || url.matches("(?i).*\\.(jpe?g|png|webp)(\\?.*)?$");
    }

    /** Markup and entities out, white space folded. */
    static String plain(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]*>", " ");
        return unescape(s).replaceAll("[\\s\\u00A0]+", " ").replaceAll(" ([.,!?;:])", "$1").trim();
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static final String[] DATES = {"EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssX"};

    /** RFC 822 (RSS) or ISO 8601 (Atom, Telegram); 0 when unreadable. */
    static long date(String s) {
        for (String f : DATES) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
                p.setLenient(false);
                return p.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String id(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Long.toHexString(h);
    }
}
