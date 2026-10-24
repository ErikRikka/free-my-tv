package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the assistant can do on the TV. ChatGPT calls these by name (Brain); each returns a short text it can report. */
final class TvTools {
    private TvTools() {}

    /** Starts an activity the way the assistant is allowed to (set by the voice session). */
    interface Launcher {
        boolean start(Intent intent);
    }

    static volatile Launcher launcher;
    /** Set when a tool opened something else: the assistant's panel closes after it has said so. */
    static volatile boolean left;

    private static final String[][] NAMES = {
            {"com.google.android.youtube.tv", "YouTube", "ютуб, youtube, ю туб"},
            {"watch.kino", "KINO.WATCH", "киновотч, кино воч, кинопаб, kino.pub, kinopub"},
            {"ru.kinopoisk.tv", "Кинопоиск", "kinopoisk, кинопоиск"},
            {"com.redshieldvpn.app", "Red Shield VPN", "впн, vpn, ред шилд"},
            {"com.spotify.tv.android", "Spotify", "спотифай, спотик"},
            {"tv.twitch.android.app", "Twitch", "твич"},
            {"org.xbmc.kodi", "Kodi", "коди"},
    };

    private static JSONObject fn(String name, String description, JSONObject properties, String... required) throws Exception {
        JSONObject params = new JSONObject().put("type", "object").put("properties", properties);
        params.put("required", new JSONArray(java.util.Arrays.asList(required)));
        return new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", name).put("description", description).put("parameters", params));
    }

    private static JSONObject str(String description) throws Exception {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONObject integer(String description) throws Exception {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    static JSONArray schemas(Context c) throws Exception {
        JSONArray apps = new JSONArray();
        for (String[] a : launchable(c)) apps.put(a[1]);
        JSONArray t = new JSONArray();
        t.put(fn("open_app", "Открыть приложение на телевизоре. Киновотч и кинопаб это KINO.WATCH.",
                new JSONObject().put("app", new JSONObject().put("type", "string").put("enum", apps)
                        .put("description", "Название приложения из списка")), "app"));
        t.put(fn("play_movie", "Найти фильм или сериал в KINO.WATCH и открыть его страницу. Название по-русски.",
                new JSONObject().put("title", str("Название фильма или сериала"))
                        .put("year", integer("Год выхода, если известен (иначе 0)"))
                        .put("kind", new JSONObject().put("type", "string").put("enum", new JSONArray().put("movie").put("serial").put("any"))
                                .put("description", "movie фильм, serial сериал, any неизвестно")), "title"));
        t.put(fn("youtube", "Найти видео на YouTube и открыть его (или открыть YouTube с поиском).",
                new JSONObject().put("query", str("Что искать, например название ролика или канала")), "query"));
        t.put(fn("set_sleep_timer", "Поставить таймер сна: через сколько минут телевизор уйдёт в режим ожидания. 0 выключает таймер.",
                new JSONObject().put("minutes", integer("Минуты, от 0 до 240")), "minutes"));
        t.put(fn("set_volume", "Изменить громкость телевизора: точный уровень в процентах (0 это без звука) или louder/quieter на шаг.",
                new JSONObject().put("percent", integer("Уровень 0-100, либо -1 если используется step"))
                        .put("step", new JSONObject().put("type", "string").put("enum", new JSONArray().put("louder").put("quieter").put("none"))
                                .put("description", "louder громче, quieter тише, none если задан percent")), "percent"));
        t.put(fn("weather", "Погода сейчас, на сегодня и на завтра: здесь (Санкт-Петербург) или в любом городе мира.",
                new JSONObject().put("city", str("Город, если спрашивают не про здесь; иначе пусто"))));
        t.put(fn("remember", "Запомнить надолго факт о собеседнике или его просьбу: имя, вкусы, близких, привычки, планы.",
                new JSONObject().put("fact", str("Факт короткой фразой от третьего лица, например «Его зовут Эрик»")), "fact"));
        t.put(fn("recall", "Поискать в долгой памяти старые факты о собеседнике: людей, вкусы, события, договорённости.",
                new JSONObject().put("query", str("Ключевые слова, например «сестра день рождения» или «любимые фильмы»")), "query"));
        t.put(fn("forget", "Забыть то, что просят забыть.",
                new JSONObject().put("what", str("Слово или фраза, по которой найти факт; «всё», чтобы очистить всю память")), "what"));
        t.put(fn("web_search", "Поискать в интернете: новости, курсы, спорт, события, расписания, цены, свежие факты. "
                        + "Возвращает короткую сводку найденного.",
                new JSONObject().put("query", str("Что искать, полным вопросом по-русски")), "query"));
        t.put(fn("turn_off_tv", "Выключить телевизор (режим ожидания).", new JSONObject()));
        return t;
    }

    static String run(Context c, String name, JSONObject a) throws Exception {
        switch (name) {
            case "open_app":
                return openApp(c, a.optString("app"));
            case "play_movie":
                return playMovie(c, a.optString("title"), a.optInt("year"), a.optString("kind", "any"));
            case "youtube":
                return youtube(c, a.optString("query"));
            case "set_sleep_timer": {
                int minutes = Math.max(0, Math.min(240, a.optInt("minutes")));
                SleepTimer.startMinutes(c, minutes);
                return minutes == 0 ? "Таймер сна выключен." : "Таймер сна поставлен на " + minutes + " мин.";
            }
            case "set_volume":
                return volume(c, a.optInt("percent", -1), a.optString("step", "none"));
            case "weather":
                return a.optString("city").trim().isEmpty() ? weather(c) : weatherIn(a.optString("city").trim());
            case "remember":
                return Memory.remember(c, a.optString("fact"));
            case "recall":
                return Memory.recall(c, a.optString("query"));
            case "forget":
                return Memory.forget(c, a.optString("what"));
            case "web_search":
                return search(a.optString("query"));
            case "turn_off_tv":
                try (Adb adb = Adb.connect(c, 8_000, null)) {
                    adb.shell("input keyevent KEYCODE_SLEEP");
                }
                return "Телевизор выключается.";
            default:
                return "Неизвестная функция " + name;
        }
    }

    /** {package, label} of what can be opened from the home screen, our own app left out. */
    private static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                for (String[] n : NAMES) if (n[0].equals(pkg)) label = n[1];
                out.add(new String[] {pkg, label});
            }
        }
        return out;
    }

    private static boolean start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Launcher l = launcher;
        try {
            if (l != null && l.start(i)) {
                left = true;
                return true;
            }
            c.startActivity(i);
            left = true;
            return true;
        } catch (RuntimeException e) {
            AppLog.w("voice: couldn't start " + i + ": " + e.getMessage());
            return false;
        }
    }

    private static String openApp(Context c, String app) {
        String want = app.toLowerCase(Locale.ROOT).trim();
        for (String[] a : launchable(c)) {
            if (a[1].toLowerCase(Locale.ROOT).equals(want)) {
                Intent i = HomeApps.launchIntent(c.getPackageManager(), a[0]);
                if (i != null && start(c, i)) return "Открыл " + a[1] + ".";
            }
        }
        return "Не нашёл приложение «" + app + "».";
    }

    private static String playMovie(Context c, String title, int year, String kind) throws Exception {
        if (title.trim().isEmpty()) return "Не расслышал название.";
        JSONObject hit = Recs.get(c).findKinopub(title, year, kind);
        if (hit == null) return "В KINO.WATCH не нашёл «" + title + "».";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://kino.watch/item/view/" + hit.optInt("id"))).setPackage(Recs.KINOWATCH);
        if (!start(c, i)) return "Нашёл «" + hit.optString("title") + "», но не смог открыть KINO.WATCH.";
        return "Открыл в KINO.WATCH: " + hit.optString("title") + (hit.optInt("year") > 0 ? " (" + hit.optInt("year") + ")" : "") + ".";
    }

    private static String youtube(Context c, String query) throws Exception {
        String id = null;
        try {
            id = Recs.get(c).youtubeFirst(query);
        } catch (Exception e) {
            AppLog.w("voice: youtube search: " + e.getMessage());
        }
        Uri uri = id != null ? Uri.parse("https://www.youtube.com/watch?v=" + id)
                : Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
        if (!start(c, new Intent(Intent.ACTION_VIEW, uri).setPackage(Recs.YOUTUBE))) return "Не получилось открыть YouTube.";
        return id != null ? "Включил на YouTube по запросу «" + query + "»." : "Открыл поиск YouTube по запросу «" + query + "».";
    }

    private static String volume(Context c, int percent, String step) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = now;
        if (percent >= 0) target = Math.round(max * Math.min(100, percent) / 100f);
        else if (step.equals("louder")) target = Math.min(max, now + Math.max(1, max / 10));
        else if (step.equals("quieter")) target = Math.max(0, now - Math.max(1, max / 10));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        return target == 0 ? "Звук выключен." : "Громкость " + Math.round(100f * target / max) + " процентов.";
    }

    private static JSONObject getJson(String url) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(15000);
        try (java.io.InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            h.disconnect();
        }
    }

    /** Any city in the world, from Open-Meteo (no key needed). */
    private static String weatherIn(String city) throws Exception {
        JSONArray found = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + Uri.encode(city))
                .optJSONArray("results");
        if (found == null || found.length() == 0) return "Не нашёл город «" + city + "».";
        JSONObject place = found.getJSONObject(0);
        JSONObject w = getJson(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,weather_code"
                + "&timezone=auto&forecast_days=2", place.getDouble("latitude"), place.getDouble("longitude")));
        JSONObject now = w.getJSONObject("current"), daily = w.getJSONObject("daily");
        JSONArray max = daily.getJSONArray("temperature_2m_max"), min = daily.getJSONArray("temperature_2m_min"),
                code = daily.getJSONArray("weather_code");
        String where = place.optString("name") + (place.has("country") ? ", " + place.optString("country") : "");
        return where + ": сейчас " + HomeData.degrees(now.getDouble("temperature_2m")) + ", "
                + HomeData.condition(now.getInt("weather_code"), now.optInt("is_day", 1) == 1) + ". Сегодня от "
                + HomeData.degrees(min.getDouble(0)) + " до " + HomeData.degrees(max.getDouble(0)) + ". Завтра от "
                + HomeData.degrees(min.getDouble(1)) + " до " + HomeData.degrees(max.getDouble(1)) + ", "
                + HomeData.condition(code.getInt(1), true) + ". Местное время " + now.optString("time").replaceFirst(".*T", "") + ".";
    }

    /** The web, through OpenAI's own search (a small model reads the pages and sums them up for speaking). */
    private static String search(String query) throws Exception {
        if (query.trim().isEmpty()) return "Пустой запрос.";
        JSONObject req = new JSONObject().put("model", "gpt-5.4-mini").put("reasoning", new JSONObject().put("effort", "none"))
                .put("tools", new JSONArray().put(new JSONObject().put("type", "web_search").put("search_context_size", "low").put("user_location", new JSONObject()
                        .put("type", "approximate").put("country", "RU").put("city", "Saint Petersburg"))))
                .put("instructions", "Найди ответ в интернете и перескажи его по-русски для голосового помощника: 2-5 коротких "
                        + "предложений, только факты, с датами, если важны, без ссылок и markdown. В конце коротко назови источник.")
                .put("input", query);
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://api.openai.com/v1/responses").openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(40000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Authorization", "Bearer " + Config.OPENAI_KEY);
        h.setRequestProperty("content-type", "application/json");
        try {
            try (java.io.OutputStream o = h.getOutputStream()) {
                o.write(req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (h.getResponseCode() != 200) return "Поиск не ответил (HTTP " + h.getResponseCode() + ").";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONArray output = new JSONObject(out.toString("UTF-8")).getJSONArray("output");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONArray content = output.getJSONObject(i).optJSONArray("content");
                for (int j = 0; content != null && j < content.length(); j++) text.append(content.getJSONObject(j).optString("text"));
            }
            String answer = text.toString().replaceAll("\\(?\\[([^\\]]*)\\]\\([^)]*\\)\\)?", "").replaceAll("\\s+", " ").trim();
            return answer.isEmpty() ? "Ничего не нашёл." : answer;
        } finally {
            h.disconnect();
        }
    }

    private static String weather(Context c) {
        HomeData.Weather w = HomeData.get(c).weather;
        if (Double.isNaN(w.temp)) return "Данных о погоде пока нет.";
        StringBuilder s = new StringBuilder("Сейчас " + HomeData.degrees(w.temp) + ", " + HomeData.condition(w.code, w.isDay)
                + ". Сегодня от " + HomeData.degrees(w.min) + " до " + HomeData.degrees(w.max) + ".");
        if (!Double.isNaN(w.tomorrowMax)) {
            s.append(" Завтра от ").append(HomeData.degrees(w.tomorrowMin)).append(" до ").append(HomeData.degrees(w.tomorrowMax))
                    .append(", ").append(HomeData.condition(w.tomorrowCode, true)).append(".");
        }
        return s.toString();
    }
}
