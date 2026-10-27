package io.freemytv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The News tile's feed: one story to a screen (its picture, topic, source, headline and a short retelling). Up and Down
 * go through the stories, Right asks for more like this (or takes it back), OK tells the story in detail, Back leaves.
 * A story counts as read after two seconds on screen.
 */
public class NewsActivity extends Activity {
    private static final long READ_AFTER = 2000, CACHE_MAX = 60L * 1024 * 1024;
    private static final int HOT = 0xFFFF6A4D, USEFUL = 0xFF6FD3A8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private News news;
    private List<NewsOrder.Story> stories;
    private int index;
    private float h, w;
    private FrameLayout root;
    private View page;
    private Bars bars;
    private TextView count, hints, note, detailsTitle, detailsText;
    private ScrollView details;
    private ReelsActivity.Sign heart, loading;
    private ObjectAnimator spin;
    private boolean waitingDetails;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        h = dm.heightPixels;
        w = dm.widthPixels;
        news = News.get(this);
        stories = news.unreadOrdered();
        String start = getIntent().getStringExtra("story");
        for (int i = 0; start != null && i < stories.size(); i++) if (stories.get(i).id.equals(start)) index = i;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bars = new Bars(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.004f) + 1, Gravity.TOP);
        bp.leftMargin = bp.rightMargin = Math.round(h * 0.074f);
        bp.topMargin = Math.round(h * 0.046f);
        count = text(Ui.semibold, 0.024f, 0xFFFFFFFF);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        cp.rightMargin = Math.round(h * 0.074f);
        cp.bottomMargin = Math.round(h * 0.1f);
        hints = text(Ui.medium, 0.02f, 0x80FFFFFF);
        hints.setGravity(Gravity.END);
        hints.setText(Ui.tr("▲▼ próximas notícias", "▲▼ next stories") + "\n" + Ui.tr("OK — detalhes", "OK — details") + "\n"
                + Ui.tr("▶ mais assim", "▶ more like this"));
        hints.setLineSpacing(0, 1.3f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        hp.rightMargin = Math.round(h * 0.074f);
        hp.bottomMargin = Math.round(h * 0.074f) - Math.round(h * 0.004f);
        cp.bottomMargin = hp.bottomMargin + Math.round(h * 0.11f);

        int sign = Math.round(h * 0.2f);
        heart = new ReelsActivity.Sign(this);
        heart.setAlpha(0f);
        loading = new ReelsActivity.Sign(this);
        loading.set(ReelsActivity.Sign.LOADING);
        loading.setVisibility(View.GONE);
        spin = ObjectAnimator.ofFloat(loading, "rotation", 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

        details = new ScrollView(this);
        details.setBackgroundColor(0xE0000000);
        details.setVisibility(View.GONE);
        details.setFillViewport(true);
        details.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(h * 0.1f);
        col.setPadding(pad, pad, pad, pad);
        detailsTitle = text(Ui.displaySemibold, 0.05f, Color.WHITE);
        detailsTitle.setLineSpacing(0, 1.05f);
        col.addView(detailsTitle, new LinearLayout.LayoutParams(Math.round(w * 0.6f), ViewGroup.LayoutParams.WRAP_CONTENT));
        detailsText = text(Ui.regular, 0.03f, 0xEBFFFFFF);
        detailsText.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Math.round(w * 0.6f), ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.topMargin = Math.round(h * 0.035f);
        col.addView(detailsText, dp);
        details.addView(col);

        note = text(Ui.medium, 0.03f, Color.WHITE);
        note.setPadding(Math.round(h / 40), Math.round(h / 80), Math.round(h / 40), Math.round(h / 80));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xCC1E1E22);
        pill.setCornerRadius(h * 0.05f);
        note.setBackground(pill);
        note.setAlpha(0f);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        np.topMargin = Math.round(h / 12);

        root.addView(bars, bp);
        root.addView(count, cp);
        root.addView(hints, hp);
        root.addView(details, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(heart, new FrameLayout.LayoutParams(sign, sign, Gravity.CENTER));
        root.addView(loading, new FrameLayout.LayoutParams(sign / 2, sign / 2, Gravity.CENTER));
        root.addView(note, np);
        setContentView(root);
        show(0);
    }

    private TextView text(android.graphics.Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * size);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The story at index on screen, slid in from below (dir 1), above (-1) or just there (0). */
    private void show(int dir) {
        ui.removeCallbacks(markRead);
        View old = page;
        page = index < stories.size() ? storyPage(stories.get(index)) : endPage();
        root.addView(page, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (old != null) {
            float d = h * 0.05f * dir;
            page.setAlpha(0f);
            page.setTranslationY(d);
            page.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            old.animate().alpha(0f).translationY(-d).setDuration(220).withEndAction(() -> root.removeView(old)).start();
        }
        boolean story = index < stories.size();
        bars.setVisibility(story ? View.VISIBLE : View.GONE);
        count.setVisibility(story ? View.VISIBLE : View.GONE);
        hints.setVisibility(story ? View.VISIBLE : View.GONE);
        count.setText((index + 1) + " / " + stories.size());
        bars.invalidate();
        if (story) {
            ui.postDelayed(markRead, READ_AFTER);
            if (index + 1 < stories.size()) prefetch(stories.get(index + 1).image);
        }
    }

    private final Runnable markRead = () -> {
        if (index < stories.size()) news.markRead(stories.get(index).id);
    };

    private View storyPage(NewsOrder.Story s) {
        FrameLayout f = new FrameLayout(this);
        TextView tile = text(Ui.displaySemibold, 0.16f, 0x14FFFFFF);   // no picture: the topic, large and faint
        tile.setText(News.topic(s.topic));
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundColor(0xFF1B1D24);
        f.addView(tile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        load(s.image, img);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF5000000, 0xD9000000, 0x99000000, 0x33000000, 0x00000000}));   // dark enough under the text on a white photo
        f.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.85f), Gravity.BOTTOM));
        View top = new View(this);   // and a little at the top, for the bars
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x73000000, 0x00000000}));
        f.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.16f), Gravity.TOP));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        if (s.topic != null && !s.topic.isEmpty()) {
            TextView chip = text(Ui.semibold, 0.021f, Color.WHITE);
            chip.setText(News.topic(s.topic));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x8C000000);
            bg.setCornerRadius(h);
            chip.setBackground(bg);
            int px = Math.round(h * 0.014f), py = Math.round(h * 0.007f);
            chip.setPadding(px, py, px, py);
            line.addView(chip);
        }
        if ("hot".equals(s.flag) || "practical".equals(s.flag)) {
            TextView flag = text(Ui.semibold, 0.021f, "hot".equals(s.flag) ? HOT : USEFUL);
            flag.setText("● " + ("hot".equals(s.flag) ? Ui.tr("em alta", "talked about") : Ui.tr("importante para a viagem", "useful for the trip")));
            line.addView(flag, margins(Math.round(h * 0.018f), 0));
        }
        TextView meta = text(Ui.medium, 0.021f, 0xB3FFFFFF);
        meta.setText(s.source + " · " + when(s.at));
        line.addView(meta, margins(Math.round(h * 0.018f), 0));
        body.addView(line);
        TextView title = text(Ui.displaySemibold, 0.058f, Color.WHITE);
        title.setText(s.title);
        title.setMaxLines(3);
        title.setLineSpacing(0, 1.05f);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(title, margins(0, Math.round(h * 0.022f)));
        TextView summary = text(Ui.regular, 0.029f, 0xC7FFFFFF);
        summary.setText(s.summary);
        summary.setMaxLines(5);
        summary.setLineSpacing(0, 1.35f);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(summary, margins(0, Math.round(h * 0.022f)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * 0.6f), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.round(h * 0.074f);
        lp.bottomMargin = Math.round(h * 0.074f);
        f.addView(body, lp);
        shade(body);
        return f;
    }

    /** A soft shadow under every line of text in it: readable on a white or busy picture too. */
    private void shade(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof ViewGroup) shade((ViewGroup) v);
            else if (v instanceof TextView && v.getBackground() == null) ((TextView) v).setShadowLayer(h * 0.008f, 0, h * 0.002f, 0xD9000000);
        }
    }

    private LinearLayout.LayoutParams margins(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }

    private View endPage() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setGravity(Gravity.CENTER);
        f.setBackgroundColor(0xFF0B0B0D);
        TextView t = text(Ui.displaySemibold, 0.05f, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setText(stories.isEmpty() ? Ui.tr("Nada novo por enquanto", "Nothing new yet") : Ui.tr("Você leu tudo", "You've read everything"));
        f.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = text(Ui.regular, 0.028f, 0xB3FFFFFF);
        long next = news.nextUpdateAt();
        sub.setText(String.format(Ui.tr("Próxima atualização por volta das %s", "Next update around %s"),
                new SimpleDateFormat("HH:mm", Locale.US).format(new Date(next))));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Math.round(h * 0.02f);
        f.addView(sub, sp);
        return f;
    }

    private String when(long at) {
        long now = System.currentTimeMillis();
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(now)).equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(at)));
        return new SimpleDateFormat(today ? "HH:mm" : "d MMM, HH:mm", Ui.ru ? Ui.RU : Locale.getDefault()).format(new Date(at));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (details.getVisibility() == View.VISIBLE) {   // the long retelling: Up and Down scroll it, Back closes it
            if (!down) return k != KeyEvent.KEYCODE_BACK || closeDetails();
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) details.smoothScrollBy(0, Math.round(h * 0.3f));
            else if (k == KeyEvent.KEYCODE_DPAD_UP) details.smoothScrollBy(0, -Math.round(h * 0.3f));
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index < stories.size()) {
                    index++;
                    show(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index > 0) {
                    index--;
                    show(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (index < stories.size() && e.getRepeatCount() == 0) like(stories.get(index));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (index < stories.size() && e.getRepeatCount() == 0 && !waitingDetails) openDetails(stories.get(index));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void like(NewsOrder.Story s) {
        boolean on = news.toggleLike(s.id);
        heart.set(on ? ReelsActivity.Sign.HEART : ReelsActivity.Sign.UNHEART);
        heart.animate().cancel();
        heart.setScaleX(0.6f);
        heart.setScaleY(0.6f);
        heart.setAlpha(1f);
        heart.animate().setStartDelay(0).scaleX(1.15f).scaleY(1.15f).setDuration(220).withEndAction(() ->
                heart.animate().alpha(0f).setStartDelay(350).setDuration(300).start()).start();
        say(on ? Ui.tr("Mais assim", "More like this") : Ui.tr("Menos disso", "Less of this"));
    }

    private void openDetails(NewsOrder.Story s) {
        waitingDetails = true;
        if (s.details == null) {
            loading.setVisibility(View.VISIBLE);
            spin.start();
        }
        news.details(s.id, text -> {
            waitingDetails = false;
            spin.cancel();
            loading.setVisibility(View.GONE);
            if (isFinishing() || index >= stories.size() || stories.get(index) != s) return;   // moved on meanwhile
            if (text == null) {
                say(Ui.tr("Não deu para carregar os detalhes", "Couldn't load details"));
                return;
            }
            detailsTitle.setText(s.title);
            detailsText.setText(text);
            details.scrollTo(0, 0);
            details.setAlpha(0f);
            details.setVisibility(View.VISIBLE);
            details.animate().alpha(1f).setDuration(200).start();
        });
    }

    private boolean closeDetails() {
        details.animate().alpha(0f).setDuration(150).withEndAction(() -> details.setVisibility(View.GONE)).start();
        return true;
    }

    private void say(String text) {
        note.setText(text);
        note.animate().cancel();
        note.setAlpha(1f);
        note.animate().alpha(0f).setStartDelay(1400).setDuration(400).withEndAction(() -> note.animate().setStartDelay(0)).start();
    }

    // MARK: - Pictures: downloaded once into the cache, decoded at screen size, off the main thread

    private void load(String url, ImageView into) {
        if (url == null) return;
        images.execute(() -> {
            Bitmap b = decode(file(url, true));
            if (b != null) ui.post(() -> {
                into.setImageBitmap(b);
                into.setAlpha(0f);
                into.animate().alpha(1f).setDuration(180).start();
            });
        });
    }

    private void prefetch(String url) {
        if (url != null) images.execute(() -> file(url, true));
    }

    private File file(String url, boolean fetch) {
        File dir = new File(getCacheDir(), "news-img");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length() + ".img");
        if (f.exists() || !fetch) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(NewsFeeds.encodeUrl(url)).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (!part.renameTo(f)) return f;
            prune(dir);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return f;
    }

    private Bitmap decode(File f) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The cache stays under 60 MB: the oldest pictures go first. */
    private static void prune(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= CACHE_MAX) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : all) {
            if (total <= CACHE_MAX * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        images.shutdownNow();
        super.onDestroy();
    }

    /** The thin bars along the top: one for each story, the ones behind filled. */
    private final class Bars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bars(android.content.Context c) {
            super(c);
        }

        @Override protected void onDraw(Canvas c) {
            int n = stories.size();
            if (n == 0) return;
            float gap = Math.min(h * 0.006f, getWidth() / (float) n * 0.3f), seg = (getWidth() - gap * (n - 1)) / n, r = getHeight() / 2f;
            for (int i = 0; i < n; i++) {
                p.setColor(i < index ? 0xBFFFFFFF : i == index ? 0xFFFFFFFF : 0x47FFFFFF);
                float x = i * (seg + gap);
                c.drawRoundRect(x, 0, x + seg, getHeight(), r, r, p);
            }
        }
    }
}
