package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title itself in its app (KINO.WATCH); null: the app's home
        String source;          // "kw" (KINO.WATCH) or "kp" (Kinopoisk's top); null: TMDB
        String plot = "";       // a short description, shown under the title
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 5;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("kinowatch", "KINO.WATCH");
        SERVICE_NAME.put("youtube", "YouTube");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final SharedPreferences ratings;   // Kinopoisk ratings by IMDb id, and favorites' TMDB ids by title
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        ratings = c.getSharedPreferences("ratings", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        String name = p.backdrop.startsWith("http") || p.backdrop.startsWith("asset:")   // addresses can be longer than a file name may be
                ? Integer.toHexString(p.backdrop.hashCode()) + "-" + p.backdrop.length() + ".jpg" : p.backdrop.replace("/", "");
        return new File(new File(context.getCacheDir(), "recs"), name);
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        refreshFeeds();
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return (p.source != null ? p.source : p.kind) + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false&without_genres=10762|10751"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 0. What TMDB recommends for the titles you love (config.properties "favorites" and the likes on the TV).
        List<Pick> tasteMovies = new ArrayList<>(), tasteShows = new ArrayList<>();
        java.util.Set<String> seeds = new java.util.LinkedHashSet<>(Prefs.likes());
        seeds.addAll(favorites());
        int used = 0;
        for (String seed : seeds) {
            int colon = seed.indexOf(':');
            if (colon < 0) continue;
            String kind = seed.substring(0, colon);
            if (!kind.equals("movie") && !kind.equals("tv")) continue;   // a feed's pick has no TMDB page: not a seed
            if (used++ >= 10) continue;
            JSONArray similar;
            try {
                similar = get("/" + kind + "/" + seed.substring(colon + 1) + "/recommendations?language=" + lang()).getJSONArray("results");
            } catch (Exception e) {
                continue;
            }
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < similar.length() && candidates.size() < 10; i++) add(candidates, similar.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                if (seeds.contains(key(pick))) continue;
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? tasteMovies : tasteShows).add(pick);
            }
        }
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=200&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=100&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, tasteMovies, tasteShows, 24);
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.ru) return "ru-RU";
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        JSONArray genreIds = o.optJSONArray("genre_ids");
        for (int i = 0; genreIds != null && i < genreIds.length(); i++) {
            int g = genreIds.optInt(i);
            if (g == 10762 || g == 10751) return;
        }
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids&include_image_language=null," + Ui.language + ",en");
        p.title = title(p, o);
        p.plot = o.optString("overview", "");
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.ru) parts.add(minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин");
            else if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
            else if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) parts.add(date.substring(0, 4));   // the year (the button already says where it plays)
        if (p.app != null) {
            String rating = rating(o);
            if (!rating.isEmpty()) parts.add(0, rating);
        }
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** "КП 7.8 · IMDb 8.1" (with a Kinopoisk key; cached two weeks), else TMDB's own rating. */
    private String rating(JSONObject o) {
        String imdb = o.optString("imdb_id", "");
        JSONObject ext = o.optJSONObject("external_ids");
        if (!imdb.startsWith("tt") && ext != null) imdb = ext.optString("imdb_id", "");
        String kpKey = Config.KINOPOISK_KEY.trim();
        if (!kpKey.isEmpty() && imdb.startsWith("tt")) {
            String saved = ratings.getString(imdb, null);
            long now = System.currentTimeMillis();
            if (saved == null || now - ratings.getLong(imdb + "@", 0) > 14 * 24 * 3600_000L) {
                try {
                    saved = kinopoisk(kpKey, imdb);
                    ratings.edit().putString(imdb, saved).putLong(imdb + "@", now).apply();
                } catch (Exception e) {
                    AppLog.w("kinopoisk: " + e.getMessage());
                }
            }
            if (saved != null && !saved.isEmpty()) return saved;
        }
        double v = o.optDouble("vote_average", 0);
        return v > 0 && o.optInt("vote_count") >= 20 ? "TMDB " + String.format(Locale.US, "%.1f", v) : "";
    }

    private static String kinopoisk(String key, String imdb) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://kinopoiskapiunofficial.tech/api/v2.2/films?imdbId="
                + URLEncoder.encode(imdb, "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-API-KEY", key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            JSONArray items = new JSONObject(read(c.getInputStream())).optJSONArray("items");
            if (items == null || items.length() == 0) return "";
            JSONObject f = items.getJSONObject(0);
            List<String> parts = new ArrayList<>();
            double kp = f.optDouble("ratingKinopoisk"), im = f.optDouble("ratingImdb");
            if (kp > 0) parts.add((Ui.ru ? "КП " : "Kinopoisk ") + String.format(Locale.US, "%.1f", kp));
            if (im > 0) parts.add("IMDb " + String.format(Locale.US, "%.1f", im));
            return String.join(" · ", parts);
        } finally {
            c.disconnect();
        }
    }

    /** config.properties "favorites" ("Interstellar; Breaking Bad") as "movie:157336", "tv:1396" (looked up once). */
    private List<String> favorites() {
        List<String> out = new ArrayList<>();
        for (String raw : Config.FAVORITES.split(";")) {
            String title = raw.trim();
            if (title.isEmpty()) continue;
            String saved = ratings.getString("fav:" + title, null);
            if (saved == null) {
                try {
                    JSONArray results = get("/search/multi?language=" + lang() + "&query=" + URLEncoder.encode(title, "UTF-8"))
                            .getJSONArray("results");
                    saved = "";
                    for (int i = 0; i < results.length() && saved.isEmpty(); i++) {
                        JSONObject r = results.getJSONObject(i);
                        String type = r.optString("media_type");
                        if (type.equals("movie") || type.equals("tv")) saved = type + ":" + r.optInt("id");
                    }
                    ratings.edit().putString("fav:" + title, saved).apply();
                } catch (Exception e) {
                    continue;
                }
            }
            if (!saved.isEmpty()) out.add(saved);
        }
        return out;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        if (Ui.en || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || Ui.language.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        stamp = stamp * 31L + Config.FAVORITES.hashCode();
        stamp = stamp * 31L + Config.KINOPOISK_KEY.hashCode();
        stamp = stamp * 31L + Prefs.likes().hashCode();   // a new like refetches with it as a seed
        return stamp;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        if (p.backdrop.startsWith("asset:")) {   // your own picture from local-assets/pics/, built into the APK
            try (InputStream in = context.getAssets().open(p.backdrop.substring(6)); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return;
        }
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(p.backdrop.startsWith("http") ? p.backdrop : IMAGES + p.backdrop).openConnection();
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (home screen backgrounds)");   // Wikimedia asks for one
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > 4) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static final long IMAGE_CACHE_BYTES = 150L * 1024 * 1024;

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        // By size, not count: the feeds hold well over a hundred pictures, and a slideshow lap shouldn't download them
        // all again. The oldest go first.
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File f : files) {
            total += f.length();
            //noinspection ResultOfMethodCallIgnored
            if (total > IMAGE_CACHE_BYTES) f.delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = toJson(picks);
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.source = o.optString("source").isEmpty() ? null : o.optString("source");
            p.plot = o.optString("plot");
            out.add(p);
        }
        return out;
    }

    // MARK: - Each app's own picks: KINO.WATCH (kino.pub: what you're watching, new and hot) and Kinopoisk (its top)

    static final String KINOWATCH = "watch.kino", KINOPOISK = "ru.kinopoisk.tv";
    /** The VPN's tile: squirrels and fox cubs (Wikimedia Commons photos), no text. */
    static final String NATURE = "nature";
    private static final String KINOPUB = "https://api.service-kp.com";
    private static final String KINOPUB_CLIENT = "client_id=xbmc&client_secret=cgg3gtifu46urtfp2zp1nqtba0k2ezxh";   // Kodi's public client
    private final Map<String, List<Pick>> feeds = new HashMap<>();
    private final ExecutorService feedIo = Executors.newSingleThreadExecutor();
    private static final int FEEDS_VERSION = 14;
    private volatile long feedsAt, feedsFailedAt;
    private volatile boolean fetchingFeeds, feedsOn;

    /**
     * Only the home screen's process fetches the feeds: kino.pub hands out a new sign-in token on every refresh, and
     * two processes refreshing it would sign each other out.
     */
    void enableFeeds() {
        if (feedsOn) return;
        feedsOn = true;
        try {
            JSONObject saved = new JSONObject(cache.getString("feeds", "{}"));
            synchronized (feeds) {
                for (java.util.Iterator<String> it = saved.keys(); it.hasNext(); ) {
                    String pkg = it.next();
                    feeds.put(pkg, parse(saved.getJSONArray(pkg)));
                }
            }
            // Saved with older texts (or another language): shown now, fetched again right away.
            feedsAt = cache.getInt("feedsVersion", 0) == FEEDS_VERSION && Ui.language.equals(cache.getString("feedsLanguage", ""))
                    ? cache.getLong("feedsAt", 0) : 0;
        } catch (Exception ignored) {
        }
    }

    /** The picks for an app's tile, or empty. */
    List<Pick> feed(String pkg) {
        synchronized (feeds) {
            List<Pick> l = feeds.get(pkg);
            return l == null ? new ArrayList<>() : new ArrayList<>(l);
        }
    }

    /** A like changes what's similar: fetch the feeds again on the next refresh. */
    void feedsChanged() {
        feedsAt = 0;
        refreshFeeds();
    }

    boolean hasFeed(String pkg) {
        synchronized (feeds) {
            List<Pick> l = feeds.get(pkg);
            return l != null && !l.isEmpty();
        }
    }

    /** The home screen's carousel: KINO.WATCH's when signed in, else TMDB's picks for the installed apps. */
    List<Pick> main() {
        if (hasFeed(KINOWATCH)) return feed(KINOWATCH);
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)) out.add(p);
        return out;
    }

    private void refreshFeeds() {
        long now = System.currentTimeMillis();
        if (!feedsOn || fetchingFeeds) return;
        if (now >= feedsAt && now - feedsAt < 30 * 60_000L) return;
        if (feedsFailedAt != 0 && now >= feedsFailedAt && now - feedsFailedAt < 5 * 60_000L) return;
        boolean kinowatch = !Config.KINOPUB_REFRESH_TOKEN.trim().isEmpty() && installed(KINOWATCH);
        boolean kinopoisk = !Config.KINOPOISK_KEY.trim().isEmpty() && hasKey(context) && installed(KINOPOISK);
        boolean youtube = youtubeConfigured() && installed(YOUTUBE);
        boolean nature = !hasFeed(NATURE) || now - cache.getLong("photosAt", 0) > 24 * 3600_000L;
        if (!kinowatch && !kinopoisk && !youtube && !nature) return;
        fetchingFeeds = true;
        feedIo.execute(() -> {
            try {
                fetchFeeds(kinowatch, kinopoisk, youtube, nature);
            } finally {
                fetchingFeeds = false;   // even after an Error, or no feed would ever be fetched again
            }
        });
    }

    private void fetchFeeds(boolean kinowatch, boolean kinopoisk, boolean youtube, boolean nature) {
        {
            boolean ok = true;
            try {
                if (kinowatch) putFeed(KINOWATCH, kinowatch());
            } catch (Exception e) {
                ok = false;
                AppLog.w("kino.watch: " + e.getMessage());
            }
            try {
                if (nature) {
                    putFeed(NATURE, nature());
                    cache.edit().putLong("photosAt", System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                AppLog.w("nature: " + e.getMessage());
            }
            try {
                if (youtube) putFeed(YOUTUBE, youtube());
            } catch (Exception e) {
                ok = false;
                AppLog.w("youtube: " + e.getMessage());
            }
            try {
                if (kinopoisk) putFeed(KINOPOISK, kinopoiskTop());
            } catch (Exception e) {
                ok = false;
                AppLog.w("kinopoisk top: " + e.getMessage());
            }
            // A failure (the TV just woke, Wi-Fi not up yet) is tried again in 5 minutes, not at the next half hour.
            if (ok) {
                feedsAt = System.currentTimeMillis();
                feedsFailedAt = 0;
            } else {
                feedsFailedAt = System.currentTimeMillis();
            }
            saveFeeds();
            notifyListeners();
        }
    }

    private void putFeed(String pkg, List<Pick> list) {
        if (list.isEmpty()) return;
        synchronized (feeds) {
            feeds.put(pkg, list);
        }
        AppLog.w("feed " + pkg + ": " + list.size());
    }

    private void saveFeeds() {
        try {
            JSONObject o = new JSONObject();
            synchronized (feeds) {
                for (Map.Entry<String, List<Pick>> e : feeds.entrySet()) o.put(e.getKey(), toJson(e.getValue()));
            }
            cache.edit().putString("feeds", o.toString()).putLong("feedsAt", feedsAt).putInt("feedsVersion", FEEDS_VERSION)
                    .putString("feedsLanguage", Ui.language).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * KINO.WATCH's own four lists, taken in turn: new movies, new shows, popular shows, popular movies (no repeats). Your
     * bookmark folders' names go to the log, in case a list should come from one of them instead.
     */
    private List<Pick> kinowatch() throws Exception {
        String token = kinopubToken();
        try {
            JSONArray folders = optItems(token, "/v1/bookmarks");
            StringBuilder names = new StringBuilder();
            for (int f = 0; folders != null && f < folders.length(); f++) names.append(f == 0 ? "" : ", ").append(folders.getJSONObject(f).optString("title"));
            AppLog.w("kino.watch bookmark folders: " + names);
        } catch (IOException e) {
            AppLog.w("kino.watch bookmarks: " + e.getMessage());
        }
        String[][] lists = {
                {"/v1/items/fresh?type=movie&perpage=20", "New"},
                {"/v1/items/fresh?type=serial&perpage=20", "New"},
                {"/v1/items/popular?type=serial&perpage=20", "Popular"},
                {"/v1/items/popular?type=movie&perpage=20", "Popular"},
        };
        JSONArray[] items = new JSONArray[lists.length];
        for (int l = 0; l < lists.length; l++) items[l] = optItems(token, lists[l][0]);
        List<Pick> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20 && out.size() < 40; i++) {
            for (int l = 0; l < lists.length && out.size() < 40; l++) {
                if (items[l] == null || i >= items[l].length()) continue;
                addKinowatch(out, seen, items[l].getJSONObject(i), lists[l][1].equals("New") ? Ui.tr("Novidade", "New") : Ui.tr("Popular", "Popular"));
            }
        }
        AppLog.w("kino.watch: " + out.size() + " from new and popular movies and shows");
        return out;
    }

    /** KINO.WATCH's best match for a title (kino.pub search): {id, title, year}, or null. */
    JSONObject findKinopub(String title, int year, String kind) throws Exception {
        if (Config.KINOPUB_REFRESH_TOKEN.trim().isEmpty()) throw new IOException("KINO.WATCH не подключён");
        String token = sharedKinopubToken();
        JSONArray items = optItems(token, "/v1/items/search?perpage=12&q=" + URLEncoder.encode(title, "UTF-8"));
        String want = normalize(title);
        JSONObject best = null;
        double bestScore = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            String full = o.optString("title");
            double score = 0;
            for (String part : full.split(" / ")) {
                String p = normalize(part);
                if (p.equals(want)) score = Math.max(score, 10);
                else if (p.startsWith(want) || want.startsWith(p)) score = Math.max(score, 6);
                else if (p.contains(want) || want.contains(p)) score = Math.max(score, 3);
            }
            if (year > 0 && o.optInt("year") == year) score += 4;
            String type = o.optString("type");
            boolean serial = type.contains("serial") || type.equals("tvshow");
            if (kind.equals("serial") && serial || kind.equals("movie") && !serial) score += 1.5;
            score -= i * 0.05;   // the search's own order breaks ties
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (best == null || bestScore < 2) return null;
        String shown = best.optString("title");
        int cut = shown.indexOf(" / ");
        return new JSONObject().put("id", best.optInt("id")).put("title", cut > 0 ? shown.substring(0, cut) : shown)
                .put("year", best.optInt("year"));
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The first YouTube video for a search (needs the YouTube sign-in), or null. */
    String youtubeFirst(String query) throws Exception {
        if (!youtubeConfigured()) return null;
        JSONArray items = youtubeGet("/search?part=snippet&type=video&maxResults=1&q=" + URLEncoder.encode(query, "UTF-8")).optJSONArray("items");
        return items == null || items.length() == 0 ? null : items.getJSONObject(0).getJSONObject("id").optString("videoId", null);
    }

    private JSONArray optItems(String token, String path) throws IOException, org.json.JSONException {
        return kinopub(token, path).optJSONArray("items");
    }

    private static void addKinowatch(List<Pick> out, java.util.Set<Integer> seen, JSONObject o, String note) throws org.json.JSONException {
        int id = o.optInt("id");
        JSONObject posters = o.optJSONObject("posters");
        String wide = posters == null ? "" : posters.optString("wide", "");
        if (id == 0 || wide.isEmpty() || seen.contains(id)) return;   // no 16:9 art: it would be a stretched poster
        JSONArray genres = o.optJSONArray("genres");
        String genre = "";
        for (int i = 0; genres != null && i < genres.length(); i++) {
            String g = genres.getJSONObject(i).optString("title").toLowerCase(Locale.ROOT);
            if (g.startsWith("детск") || g.startsWith("семейн")) return;
            if (genre.isEmpty()) genre = g;
        }
        seen.add(id);
        Pick p = new Pick();
        p.id = id;
        String type = o.optString("type");
        p.kind = type.contains("serial") || type.equals("tvshow") ? "tv" : "movie";
        String title = o.optString("title");
        int cut = title.indexOf(" / ");
        p.title = cut > 0 ? title.substring(0, cut) : title;
        List<String> parts = new ArrayList<>();
        if (!note.isEmpty()) parts.add(note);
        double kp = o.optDouble("kinopoisk_rating", 0), imdb = o.optDouble("imdb_rating", 0);
        if (kp > 0) parts.add((Ui.ru ? "КП " : "Kinopoisk ") + String.format(Locale.US, "%.1f", kp));
        if (imdb > 0) parts.add("IMDb " + String.format(Locale.US, "%.1f", imdb));
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (!genre.isEmpty()) parts.add(genre);
        if (o.optInt("year") > 0) parts.add(String.valueOf(o.optInt("year")));
        p.meta = String.join(" · ", parts);
        p.backdrop = wide;
        p.plot = o.optString("plot", "").trim();
        p.app = "kinowatch";
        p.pkg = KINOWATCH;
        p.source = "kw";
        p.link = "https://kino.watch/item/view/" + id;
        p.detailed = true;
        out.add(p);
    }

    /**
     * The access token as :home last shared it, for the voice assistant in the main process. The refresh token changes
     * with every refresh, so only :home refreshes: two processes doing it would sign each other out.
     */
    private String sharedKinopubToken() throws IOException {
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),
                    "kinopub-access.json").toPath()), StandardCharsets.UTF_8));
            if (System.currentTimeMillis() < o.optLong("until")) return o.getString("access");
        } catch (Exception ignored) {
        }
        throw new IOException("KINO.WATCH ещё не готов: открой главный экран и попробуй снова");
    }

    private void shareKinopubToken(String access, long until) {
        try {
            File f = new File(context.getFilesDir(), "kinopub-access.json"), tmp = new File(context.getFilesDir(), "kinopub-access.tmp");
            String json = new JSONObject().put("access", access).put("until", until).toString();
            if (f.exists() && json.equals(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) return;
            java.nio.file.Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception e) {
            AppLog.w("kino.watch: token not shared: " + e.getMessage());
        }
    }

    /** kino.pub's access token (a day long), refreshed with the saved refresh token, which changes every time. :home only. */
    private String kinopubToken() throws Exception {
        SharedPreferences sp = context.getSharedPreferences("kinopub", Context.MODE_PRIVATE);
        String seed = Config.KINOPUB_REFRESH_TOKEN.trim();
        if (!seed.equals(sp.getString("seed", ""))) sp.edit().clear().putString("seed", seed).putString("refresh", seed).commit();
        String access = sp.getString("access", null);
        long now = System.currentTimeMillis();
        if (access != null && now < sp.getLong("until", 0)) {
            shareKinopubToken(access, sp.getLong("until", 0));
            return access;
        }
        String refresh = sp.getString("refresh", seed);
        HttpURLConnection c = (HttpURLConnection) new URL(KINOPUB + "/oauth2/token").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(("grant_type=refresh_token&" + KINOPUB_CLIENT + "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("sign-in expired (HTTP " + code + "): run tools/kinopub_token.py again");
            JSONObject o = new JSONObject(read(c.getInputStream()));
            access = o.getString("access_token");
            long until = now + Math.max(600, o.optLong("expires_in", 86400) - 600) * 1000L;
            sp.edit().putString("access", access).putString("refresh", o.optString("refresh_token", refresh)).putLong("until", until).commit();
            shareKinopubToken(access, until);
            return access;
        } finally {
            c.disconnect();
        }
    }

    private JSONObject kinopub(String token, String path) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(KINOPUB + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = c.getResponseCode();
            if (code == 401) context.getSharedPreferences("kinopub", Context.MODE_PRIVATE).edit().remove("access").commit();
            if (code != 200) throw new IOException("HTTP " + code + " for " + path.replaceAll("\\?.*", ""));
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    // MARK: - YouTube: the newest videos from your subscriptions

    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String YT_API = "https://www.googleapis.com/youtube/v3";
    private volatile String ytToken;
    private volatile long ytTokenUntil;

    private static boolean youtubeConfigured() {
        return !Config.YOUTUBE_CLIENT_ID.trim().isEmpty() && !Config.YOUTUBE_CLIENT_SECRET.trim().isEmpty()
                && !Config.YOUTUBE_REFRESH_TOKEN.trim().isEmpty();
    }

    /** The newest videos from your subscriptions (the channels you watch most first), with one trending in every four. */
    private List<Pick> youtube() throws Exception {
        JSONArray subs = youtubeGet("/subscriptions?part=snippet&mine=true&maxResults=50&order=relevance").optJSONArray("items");
        List<String[]> recent = new ArrayList<>();   // {video id, published at}
        for (int i = 0; subs != null && i < subs.length() && i < 25; i++) {
            String channel = subs.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").optString("channelId");
            if (!channel.startsWith("UC")) continue;
            JSONArray uploads;
            try {
                uploads = youtubeGet("/playlistItems?part=contentDetails&maxResults=3&playlistId=UU" + channel.substring(2))
                        .optJSONArray("items");
            } catch (IOException e) {
                continue;   // a channel without public uploads
            }
            for (int j = 0; uploads != null && j < uploads.length(); j++) {
                JSONObject cd = uploads.getJSONObject(j).getJSONObject("contentDetails");
                recent.add(new String[] {cd.optString("videoId"), cd.optString("videoPublishedAt")});
            }
        }
        recent.sort((a, b) -> b[1].compareTo(a[1]));
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < recent.size() && i < 50; i++) ids.append(ids.length() == 0 ? "" : ",").append(recent.get(i)[0]);
        List<Pick> mine = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long now = System.currentTimeMillis();
        if (ids.length() > 0) {
            JSONArray videos = youtubeGet("/videos?part=snippet,contentDetails&id=" + ids).optJSONArray("items");
            Map<String, JSONObject> byId = new HashMap<>();
            for (int i = 0; videos != null && i < videos.length(); i++) byId.put(videos.getJSONObject(i).optString("id"), videos.getJSONObject(i));
            for (String[] r : recent) {
                JSONObject v = byId.get(r[0]);
                if (v == null || mine.size() >= 30) continue;
                addVideo(mine, seen, v, now, "");
            }
        }
        // One in four: trending in your country (YouTube's API has no home feed to read).
        List<Pick> trending = new ArrayList<>();
        try {
            JSONArray hot = youtubeGet("/videos?part=snippet,contentDetails&chart=mostPopular&maxResults=25&regionCode=" + REGION)
                    .optJSONArray("items");
            for (int i = 0; hot != null && i < hot.length(); i++) addVideo(trending, seen, hot.getJSONObject(i), now, Ui.tr("Em alta", "Trending"));
        } catch (IOException e) {
            AppLog.w("youtube trending: " + e.getMessage());
        }
        AppLog.w("youtube: " + mine.size() + " from subscriptions, " + trending.size() + " trending");
        List<Pick> out = new ArrayList<>();
        for (int i = 0, t = 0; (i < mine.size() || t < trending.size()) && out.size() < 40; ) {
            for (int n = 0; n < 3 && i < mine.size(); n++) out.add(mine.get(i++));
            if (t < trending.size()) out.add(trending.get(t++));
        }
        return out;
    }

    private static boolean addVideo(List<Pick> out, java.util.Set<String> seen, JSONObject v, long now, String note)
            throws org.json.JSONException {
        String id = v.optString("id");
        JSONObject sn = v.getJSONObject("snippet");
        long seconds = isoSeconds(v.getJSONObject("contentDetails").optString("duration"));
        if (seconds <= 180 || "live".equals(sn.optString("liveBroadcastContent")) || !seen.add(id)) return false;   // Shorts (up to 3 min), live
        JSONObject th = sn.optJSONObject("thumbnails");
        String image = "";
        for (String size : new String[] {"maxres", "standard", "high"}) {
            if (image.isEmpty() && th != null && th.optJSONObject(size) != null) image = th.getJSONObject(size).optString("url");
        }
        if (image.isEmpty()) return false;
        Pick p = new Pick();
        p.id = id.hashCode();
        p.kind = "movie";
        p.title = sn.optString("title");
        List<String> parts = new ArrayList<>();
        if (!note.isEmpty()) parts.add(note);
        parts.add(sn.optString("channelTitle"));
        long at = isoTime(sn.optString("publishedAt"));
        if (at > 0) parts.add(ago(now - at));
        parts.add(seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
        p.meta = String.join(" · ", parts);
        p.backdrop = image;
        p.app = "youtube";
        p.pkg = YOUTUBE;
        p.source = "yt";
        p.link = "https://www.youtube.com/watch?v=" + id;
        p.detailed = true;
        out.add(p);
        return true;
    }

    /**
     * Squirrels (flying ones too) and fox cubs, every other photo: Wikimedia Commons photos picked by eye, listed by page
     * id in assets/photos.txt (live animals only; a search could bring up a roadkill or a specimen). Wide, 1920 px.
     */
    private List<Pick> nature() throws Exception {
        List<Integer> squirrels = new ArrayList<>(), foxes = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                context.getAssets().open("photos.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("#")[0].trim().split("\\s+");
                if (f.length != 2) continue;
                (f[0].equals("fox") ? foxes : squirrels).add(Integer.parseInt(f[1]));
            }
        }
        java.util.Collections.shuffle(squirrels);
        java.util.Collections.shuffle(foxes);
        List<Pick> a = commonsPhotos(squirrels.subList(0, Math.min(25, squirrels.size())));
        List<Pick> b = commonsPhotos(foxes.subList(0, Math.min(25, foxes.size())));
        AppLog.w("photos: " + a.size() + " squirrels, " + b.size() + " foxes");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        // Your own pictures (local-assets/pics/, e.g. funny or generated ones) go in among them, one in every three.
        try {
            String[] mine = context.getAssets().list("pics");
            for (int j = 0; mine != null && j < mine.length; j++) {
                Pick p = new Pick();
                p.id = mine[j].hashCode();
                p.kind = "movie";
                p.title = "";
                p.backdrop = "asset:pics/" + mine[j];
                p.source = NATURE;
                p.detailed = true;
                out.add(Math.min(out.size(), 2 + 3 * j), p);
            }
        } catch (IOException e) {
            AppLog.w("own pictures: " + e.getMessage());
        }
        return out;
    }

    private List<Pick> commonsPhotos(List<Integer> ids) throws Exception {
        List<Pick> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 50) {
            StringBuilder list = new StringBuilder();
            for (int i = from; i < Math.min(ids.size(), from + 50); i++) list.append(i == from ? "" : "|").append(ids.get(i));
            HttpURLConnection c = (HttpURLConnection) new URL("https://commons.wikimedia.org/w/api.php?action=query&format=json"
                    + "&prop=imageinfo&iiprop=url&iiurlwidth=1920&pageids=" + URLEncoder.encode(list.toString(), "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (home screen backgrounds)");
            JSONObject pages;
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                JSONObject q = new JSONObject(read(c.getInputStream())).optJSONObject("query");
                pages = q == null ? null : q.optJSONObject("pages");
            } finally {
                c.disconnect();
            }
            for (java.util.Iterator<String> it = pages == null ? null : pages.keys(); it != null && it.hasNext(); ) {
                JSONObject page = pages.getJSONObject(it.next());
                JSONArray info = page.optJSONArray("imageinfo");
                String url = info == null || info.length() == 0 ? "" : info.getJSONObject(0).optString("thumburl", "");
                if (url.isEmpty()) continue;
                Pick p = new Pick();
                p.id = page.optInt("pageid");
                p.kind = "movie";
                p.title = "";
                p.backdrop = url;
                p.source = NATURE;
                p.detailed = true;
                out.add(p);
            }
        }
        java.util.Collections.shuffle(out);
        return out;
    }

    /** "3 h ago", "2 days ago" (in the TV's language). */
    private static String ago(long ms) {
        long h = ms / 3600_000L;
        if (h < 1) return Ui.tr("agora", "just now");
        if (h < 24) return Ui.ru ? Russian.count(h, "час", "часа", "часов") + " назад" : Ui.tr("há " + h + " h", h + " h ago");
        long d = h / 24;
        return Ui.ru ? Russian.count(d, "день", "дня", "дней") + " назад" : Ui.tr("há " + d + " d", d + " d ago");
    }

    private static long isoTime(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "PT1H2M3S" → 3723. */
    private static long isoSeconds(String s) {
        long total = 0, n = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) n = n * 10 + (ch - '0');
            else {
                if (ch == 'H') total += n * 3600;
                else if (ch == 'M') total += n * 60;
                else if (ch == 'S') total += n;
                else if (ch == 'D') total += n * 86400;
                n = 0;
            }
        }
        return total;
    }

    private JSONObject youtubeGet(String path) throws IOException, org.json.JSONException {
        if (ytToken == null || System.currentTimeMillis() > ytTokenUntil) {
            HttpURLConnection c = (HttpURLConnection) new URL("https://oauth2.googleapis.com/token").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try {
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(("grant_type=refresh_token&client_id=" + URLEncoder.encode(Config.YOUTUBE_CLIENT_ID.trim(), "UTF-8")
                            + "&client_secret=" + URLEncoder.encode(Config.YOUTUBE_CLIENT_SECRET.trim(), "UTF-8")
                            + "&refresh_token=" + URLEncoder.encode(Config.YOUTUBE_REFRESH_TOKEN.trim(), "UTF-8"))
                            .getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("Google sign-in expired (HTTP " + code + "): run tools/youtube_token.py again");
                JSONObject o = new JSONObject(read(c.getInputStream()));
                ytToken = o.getString("access_token");
                ytTokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000L;
            } finally {
                c.disconnect();
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(YT_API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + ytToken);
        try {
            int code = c.getResponseCode();
            if (code == 401) ytToken = null;
            if (code != 200) throw new IOException("YouTube HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /**
     * What's popular on Kinopoisk and can be watched there now: Kinopoisk's own top (it lists upcoming releases too)
     * and TMDB's most popular on Kinopoisk's subscription in Russia, each one kept only if TMDB says it's streaming on
     * Kinopoisk today (fetchDetails reads where it streams).
     */
    private List<Pick> kinopoiskTop() throws Exception {
        List<Pick> candidates = new ArrayList<>();
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://kinopoiskapiunofficial.tech/api/v2.2/films/collections?type=TOP_POPULAR_ALL&page=1").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-API-KEY", Config.KINOPOISK_KEY.trim());
        try {
            int code = c.getResponseCode();
            JSONArray items = code == 200 ? new JSONObject(read(c.getInputStream())).optJSONArray("items") : null;
            if (code != 200) AppLog.w("kinopoisk top: HTTP " + code);
            for (int i = 0; items != null && i < items.length(); i++) {
                String imdb = items.getJSONObject(i).optString("imdbId", "");
                if (!imdb.startsWith("tt")) continue;
                try {
                    JSONObject found = get("/find/" + imdb + "?external_source=imdb_id&language=" + lang());
                    JSONArray m = found.optJSONArray("movie_results"), t = found.optJSONArray("tv_results");
                    if (m != null && m.length() > 0) add(candidates, m.getJSONObject(0), "movie");
                    else if (t != null && t.length() > 0) add(candidates, t.getJSONObject(0), "tv");
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.disconnect();
        }
        int provider = kinopoiskProvider();
        if (provider != 0) {
            String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_providers=" + provider
                    + "&with_watch_monetization_types=flatrate&without_genres=10762|10751&sort_by=popularity.desc";
            JSONArray movies = get("/discover/movie?vote_count.gte=100" + common).getJSONArray("results");
            JSONArray shows = get("/discover/tv?vote_count.gte=50" + common).getJSONArray("results");
            for (int i = 0; i < Math.max(movies.length(), shows.length()); i++) {
                if (i < movies.length()) add(candidates, movies.getJSONObject(i), "movie");
                if (i < shows.length()) add(candidates, shows.getJSONObject(i), "tv");
            }
        }
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int checked = 0;
        for (Pick p : candidates) {
            if (out.size() >= 30 || checked >= 45) break;
            if (!seen.add(key(p))) continue;
            checked++;
            try {
                fetchDetails(p);
            } catch (Exception e) {
                continue;
            }
            if (KINOPOISK.equals(p.pkg)) out.add(p);
        }
        AppLog.w("kinopoisk: " + out.size() + " watchable of " + checked + " checked");
        return out;
    }

    /** Kinopoisk's id among TMDB's streaming services in your country (0 if TMDB doesn't list it there). */
    private int kinopoiskProvider() {
        int saved = ratings.getInt("kpProvider:" + REGION, -1);
        if (saved >= 0) return saved;
        try {
            JSONArray list = get("/watch/providers/movie?watch_region=" + REGION).getJSONArray("results");
            int id = 0;
            for (int i = 0; i < list.length() && id == 0; i++) {
                if (list.getJSONObject(i).optString("provider_name").toLowerCase(Locale.ROOT).contains("kinopoisk")) {
                    id = list.getJSONObject(i).optInt("provider_id");
                }
            }
            ratings.edit().putInt("kpProvider:" + REGION, id).apply();
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONArray toJson(List<Pick> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : list) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                    .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("detailed", p.detailed).put("link", p.link == null ? "" : p.link)
                    .put("source", p.source == null ? "" : p.source).put("plot", p.plot == null ? "" : p.plot));
        }
        return a;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
