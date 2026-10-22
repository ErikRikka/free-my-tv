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
        t.put(fn("weather", "Узнать погоду сейчас, на сегодня и на завтра.", new JSONObject()));
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
                return weather(c);
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
