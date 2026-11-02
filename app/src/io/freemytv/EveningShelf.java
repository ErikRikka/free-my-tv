package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tonight's picks in the drawer, in the weather card's place: 4 portrait posters on the left, and on the right the
 * "Cringe for tonight" shelf (3 tips as rows, then "All 7 tips"), plus the full list drawn over the drawer. Pictures
 * are fetched and shaped (cropped, rounded, a YouTube still whole over its blurred copy) on a worker thread; text is
 * ellipsized when the picks or the layout change. Drawing allocates nothing.
 */
final class EveningShelf {
    static final int AMBER = 0xFFEF9F27, AMBER_INK = 0xFF412402;
    private static final int MAX_ROWS = 3;

    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> images = new HashMap<>();
    private final List<String> loading = new ArrayList<>();

    private List<EveningPicks.Item> posters = new ArrayList<>(), cringe = new ArrayList<>();
    private String built = "";
    final RectF card = new RectF(), list = new RectF();
    private final RectF[] posterRects = rects(4), rowRects = rects(MAX_ROWS + 1), listRects = rects(7);
    private final String[] posterTitle = new String[4], posterWhy = new String[4], rowTitle = new String[7], rowWhy = new String[7];
    Spring[] focus = springs(4 + MAX_ROWS + 1), listFocus = springs(7);
    private float k = 1;
    // Built when the picks or the layout change, so drawing allocates nothing
    private String tonightLabel = "", builtLabel = "", cringeLabel = "", allLabel = "", tipOnly = "", seenLabel = "";
    private float tonightW, vhsW, allW, tipOnlyW, seenW;
    /** The focused row's "Watched" button has the focus instead of the row (→ from the row). */
    boolean checkFocused;
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            title = new TextPaint(Paint.ANTI_ALIAS_FLAG), why = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    EveningShelf(Runnable changed) {
        this.changed = changed;
        head.setTypeface(Ui.semibold);
        small.setTypeface(Ui.medium);
        title.setTypeface(Ui.semibold);
        why.setTypeface(Ui.medium);
        chip.setTypeface(Ui.semibold);
    }

    boolean visible() {
        return !posters.isEmpty() || !cringe.isEmpty();
    }

    int posterCount() {
        return posters.size();
    }

    int rowCount() {
        return Math.min(MAX_ROWS, cringe.size());
    }

    /** Whether the shelf ends with "All N tips" (more tips than rows). */
    boolean hasAll() {
        return cringe.size() > MAX_ROWS;
    }

    int listCount() {
        return Math.min(7, cringe.size());
    }

    /** Focus index → the pick it stands for (null for "All N tips"). Posters 0–3, rows 4–6, "All" 7. */
    EveningPicks.Item at(int index) {
        if (index < 4) return index < posters.size() ? posters.get(index) : null;
        int r = index - 4;
        return r < rowCount() ? cringe.get(r) : null;
    }

    EveningPicks.Item listItem(int i) {
        return i < cringe.size() ? cringe.get(i) : null;
    }

    RectF rectOf(int index) {
        return index < 4 ? posterRects[index] : rowRects[index - 4];
    }

    void setPicks(List<EveningPicks.Item> p, List<EveningPicks.Item> c, long builtAt) {
        posters = new ArrayList<>(p.subList(0, Math.min(4, p.size())));
        cringe = new ArrayList<>(c.subList(0, Math.min(7, c.size())));
        built = builtAt <= 0 ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date(builtAt));
        prepareText();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (EveningPicks.Item i : posters) keep.add(i.image);
        for (EveningPicks.Item i : cringe) keep.add(i.image);
        synchronized (images) {   // yesterday's pictures go: the launcher runs for weeks
            for (java.util.Iterator<Map.Entry<String, Bitmap>> it = images.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Bitmap> e = it.next();
                if (!keep.contains(e.getKey())) {
                    e.getValue().recycle();
                    it.remove();
                }
            }
        }
        for (int i = 0; i < posters.size(); i++) want(posters.get(i).image, true);
        for (EveningPicks.Item i : cringe) want(i.image, false);
    }

    /** The card fills `r`; the list covers `listArea` when open. */
    void layout(RectF r, RectF listArea, float scale) {
        k = scale;
        card.set(r);
        list.set(listArea);
        head.setTextSize(27 * k);
        small.setTextSize(19 * k);
        title.setTextSize(21 * k);
        why.setTextSize(17 * k);
        chip.setTextSize(15 * k);
        float pad = 28 * k, top = r.top + 70 * k, bottom = r.bottom - 26 * k, h = bottom - top, w = h * 2 / 3, gap = 22 * k;
        for (int i = 0; i < 4; i++) posterRects[i].set(r.left + pad + i * (w + gap), top, r.left + pad + i * (w + gap) + w, bottom);
        float sx = posterRects[3].right + 60 * k, rowH = 66 * k, rowGap = 8 * k;
        for (int i = 0; i < MAX_ROWS; i++) rowRects[i].set(sx, top + i * (rowH + rowGap), r.right - pad, top + i * (rowH + rowGap) + rowH);
        rowRects[MAX_ROWS].set(sx, top + MAX_ROWS * (rowH + rowGap), sx + 300 * k, bottom);
        float lt = listArea.top + 78 * k, lh = (listArea.bottom - 24 * k - lt) / 7;
        for (int i = 0; i < 7; i++) listRects[i].set(listArea.left + pad, lt + i * lh, listArea.right - pad, lt + i * lh + lh - 6 * k);
        prepareText();
    }

    private void prepareText() {
        tonightLabel = Ui.tr("Para esta noite", "Tonight");
        builtLabel = built.isEmpty() ? "" : Ui.tr("feito hoje às ", "picked today at ") + built;
        cringeLabel = Ui.tr("Cringe para esta noite", "Cringe for tonight");
        allLabel = String.format(Ui.tr("Todas as %d dicas  ›", "All %d tips  ›"), listCount());
        tipOnly = Ui.tr("só dica", "tip only");
        seenLabel = "✓ " + Ui.tr("Vi", "Watched");
        seenW = small.measureText(seenLabel);
        tonightW = head.measureText(tonightLabel);
        vhsW = chip.measureText("VHS");
        allW = small.measureText(allLabel);
        tipOnlyW = small.measureText(tipOnly);
        float pill = small.measureText("✓ " + Ui.tr("Vi", "Watched")) + 60 * k;
        float pw = posterRects[0].width() - 24 * k, rw = rowRects[0].width() - 140 * k - pill, lw = listRects[0].width() - 140 * k - pill;
        for (int i = 0; i < 4; i++) {
            EveningPicks.Item p = i < posters.size() ? posters.get(i) : null;
            posterTitle[i] = p == null ? "" : TextUtils.ellipsize(p.title, title, Math.max(1, pw), TextUtils.TruncateAt.END).toString();
            posterWhy[i] = p == null ? "" : TextUtils.ellipsize(p.why, why, Math.max(1, pw), TextUtils.TruncateAt.END).toString();
        }
        for (int i = 0; i < 7; i++) {
            EveningPicks.Item c = i < cringe.size() ? cringe.get(i) : null;
            float width = i < MAX_ROWS ? Math.min(rw, lw) : lw;
            rowTitle[i] = c == null ? "" : TextUtils.ellipsize(c.title, title, Math.max(1, i < MAX_ROWS ? rw : lw), TextUtils.TruncateAt.END).toString();
            rowWhy[i] = c == null ? "" : TextUtils.ellipsize(c.why, why, Math.max(1, i < MAX_ROWS ? rw : lw), TextUtils.TruncateAt.END).toString();
            if (width <= 0) rowTitle[i] = rowWhy[i] = "";
        }
    }

    boolean step(float dt) {
        boolean moving = false;
        for (Spring s : focus) moving |= s.step(dt);
        for (Spring s : listFocus) moving |= s.step(dt);
        return moving;
    }

    /** Draws the card's contents (the glass under it is the caller's). ink/dim: the drawer's text colors now. */
    void draw(Canvas c, int ink, int dim, boolean night) {
        float pad = 28 * k, base = card.top + 46 * k;
        head.setColor(ink);
        c.drawText(tonightLabel, card.left + pad, base, head);
        if (!builtLabel.isEmpty()) {
            small.setColor(dim);
            c.drawText(builtLabel, card.left + pad + tonightW + 14 * k, base, small);
        }
        int top = -1;
        for (int i = 0; i < posters.size(); i++) if (focus[i].value > 0.02f && (top < 0 || focus[i].value > focus[top].value)) top = i;
        for (int i = 0; i < posters.size(); i++) if (i != top) drawPoster(c, i);
        if (top >= 0) drawPoster(c, top);

        if (cringe.isEmpty()) return;
        float sx = rowRects[0].left;
        fill.setShader(null);
        fill.setColor(Ui.alpha(ink, 0.14f));
        c.drawRect(sx - 31 * k, card.top + 70 * k, sx - 30 * k, card.bottom - 26 * k, fill);
        float cw = vhsW + 16 * k;
        tmp.set(sx, base - 21 * k, sx + cw, base + 5 * k);
        fill.setColor(AMBER);
        c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
        chip.setColor(AMBER_INK);
        c.drawText("VHS", sx + 8 * k, base - 2 * k, chip);
        head.setColor(night ? 0xFFFAC775 : 0xFF854F0B);
        c.drawText(cringeLabel, sx + cw + 12 * k, base, head);
        for (int i = 0; i < rowCount(); i++) drawRow(c, rowRects[i], i, focus[4 + i].value, ink, night, checkFocused && focus[4 + i].target > 0.5f);
        if (hasAll()) {
            float f = Ui.clamp(focus[4 + MAX_ROWS].value);
            RectF r = rowRects[MAX_ROWS];
            small.setColor(f > 0.5f ? 0xFF111114 : dim);
            float lw = allW + 28 * k;
            tmp.set(r.left, r.centerY() - 17 * k, r.left + lw, r.centerY() + 17 * k);
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            }
            c.drawText(allLabel, r.left + 14 * k, r.centerY() + 6 * k, small);
        }
    }

    /** The whole cringe list, over the drawer (the glass under it is the caller's). */
    void drawList(Canvas c, int ink, boolean night) {
        float pad = 28 * k, base = list.top + 50 * k;
        float cw = vhsW + 16 * k;
        tmp.set(list.left + pad, base - 21 * k, list.left + pad + cw, base + 5 * k);
        fill.setShader(null);
        fill.setColor(AMBER);
        c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
        chip.setColor(AMBER_INK);
        c.drawText("VHS", list.left + pad + 8 * k, base - 2 * k, chip);
        head.setColor(night ? 0xFFFAC775 : 0xFF854F0B);
        c.drawText(cringeLabel, list.left + pad + cw + 12 * k, base, head);
        for (int i = 0; i < listCount(); i++) drawRow(c, listRects[i], i, listFocus[i].value, ink, night, checkFocused && listFocus[i].target > 0.5f);
    }

    private void drawPoster(Canvas c, int i) {
        float f = Ui.clamp(focus[i].value), s = 1 + 0.08f * f;
        RectF r = posterRects[i];
        tmp.set(r.centerX() - r.width() * s / 2, r.bottom - r.height() * s, r.centerX() + r.width() * s / 2, r.bottom);
        Bitmap b = images.get(posters.get(i).image);
        if (b != null) {
            c.drawBitmap(b, null, tmp, photo);
        } else {
            fill.setShader(null);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(tmp, 14 * k, 14 * k, fill);
        }
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(4 * k);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(tmp, 14 * k * s, 14 * k * s, fill);
            fill.setStyle(Paint.Style.FILL);
        }
        float x = tmp.left + 12 * k * s;
        title.setColor(0xFFFFFFFF);
        why.setColor(0xCCFFFFFF);
        c.save();
        c.scale(s, s, tmp.left, tmp.bottom);
        c.drawText(posterTitle[i], tmp.left + 12 * k, tmp.bottom - 36 * k, title);
        c.drawText(posterWhy[i], tmp.left + 12 * k, tmp.bottom - 13 * k, why);
        c.restore();
    }

    private void drawRow(Canvas c, RectF r, int i, float focusValue, int ink, boolean night, boolean onCheck) {
        float f = Ui.clamp(focusValue);
        if (onCheck) f = Math.min(f, 0.35f);   // the row stays lit, the button takes the white
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(r, 14 * k, 14 * k, fill);
        }
        EveningPicks.Item item = cringe.get(i);
        float th = r.height() - 10 * k, tw = th * 16 / 9;
        tmp.set(r.left + 5 * k, r.top + 5 * k, r.left + 5 * k + tw, r.top + 5 * k + th);
        Bitmap b = images.get(item.image);
        if (b != null) {
            c.drawBitmap(b, null, tmp, photo);
        } else {
            fill.setColor(night ? 0xFF633806 : 0xFFFAC775);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
        }
        float x = tmp.right + 16 * k;
        title.setColor(f > 0.5f ? 0xFF111114 : ink);
        why.setColor(f > 0.5f ? 0xFF854F0B : night ? 0xFFFAC775 : 0xFF854F0B);
        c.drawText(rowTitle[i], x, r.top + r.height() * 0.44f, title);
        c.drawText(rowWhy[i], x, r.top + r.height() * 0.80f, why);
        // "✓ Watched": a pill at the row's end, white when it has the focus (→ from the row)
        float pw = seenW + 32 * k, ph = Math.min(38 * k, r.height() - 16 * k);
        tmp.set(r.right - 10 * k - pw, r.centerY() - ph / 2, r.right - 10 * k, r.centerY() + ph / 2);
        fill.setColor(onCheck ? 0xFFFFFFFF : f > 0.5f ? 0x1A111114 : Ui.alpha(ink, 0.12f));
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
        small.setColor(onCheck || f > 0.5f ? 0xFF111114 : Ui.alpha(ink, 0.8f));
        c.drawText(seenLabel, tmp.left + 16 * k, tmp.centerY() + 6 * k, small);
        if (!item.playable()) {
            small.setColor(f > 0.5f ? 0x99111114 : Ui.alpha(ink, 0.5f));
            c.drawText(tipOnly, tmp.left - 14 * k - tipOnlyW, r.top + r.height() * 0.44f, small);
        }
    }

    // MARK: - Pictures

    private void want(String url, boolean poster) {
        if (url == null || url.isEmpty()) return;
        synchronized (images) {
            if (images.containsKey(url) || loading.contains(url)) return;
            loading.add(url);
        }
        final float scale = k;
        io.execute(() -> {
            Bitmap shaped = null;
            try {
                Bitmap src = fetch(url, poster ? 700 : 400);
                if (src != null) {
                    shaped = poster ? poster(src, Math.round(163 * scale), Math.round(244 * scale), 14 * scale)
                            : cover(src, Math.round(112 * scale), Math.round(63 * scale), 8 * scale);
                    src.recycle();
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("evening picture: " + e.getMessage());
            }
            final Bitmap done = shaped;
            main.post(() -> {
                synchronized (images) {
                    loading.remove(url);
                    if (done != null) images.put(url, done);
                }
                if (done != null) changed.run();
            });
        });
    }

    private static Bitmap fetch(String url, int maxSide) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (FreeMyTV)");
        try {
            if (c.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024; ) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= maxSide) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            c.disconnect();
        }
    }

    /** A portrait poster: a poster as it is; a wide still whole over its blurred copy; darkened at the bottom for text. */
    private static Bitmap poster(Bitmap src, int w, int h, float radius) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap face;
        if (src.getWidth() < src.getHeight()) {
            face = coverPlain(src, w, h);
        } else {
            face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas fc = new Canvas(face);
            Bitmap blur = coverPlain(src, Math.max(1, w / 16), Math.max(1, h / 16));
            fc.drawBitmap(blur, null, new RectF(0, 0, w, h), p);
            blur.recycle();
            fc.drawColor(0x66000000);
            float fh = w * src.getHeight() / (float) src.getWidth(), y = (h - fh) * 0.4f;
            fc.drawBitmap(src, null, new RectF(0, y, w, y + fh), p);
        }
        Canvas fc = new Canvas(face);
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
        fc.drawRect(0, h * 0.5f, w, h, shade);
        rounded(c, face, w, h, radius);
        face.recycle();
        return out;
    }

    private static Bitmap cover(Bitmap src, int w, int h, float radius) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap face = coverPlain(src, w, h);
        rounded(new Canvas(out), face, w, h, radius);
        face.recycle();
        return out;
    }

    private static Bitmap coverPlain(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float sw = src.getWidth() * s, sh = src.getHeight() * s;
        new Canvas(out).drawBitmap(src, null, new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static void rounded(Canvas c, Bitmap face, int w, int h, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
    }

    private static RectF[] rects(int n) {
        RectF[] r = new RectF[n];
        for (int i = 0; i < n; i++) r[i] = new RectF();
        return r;
    }

    private static Spring[] springs(int n) {
        Spring[] s = new Spring[n];
        for (int i = 0; i < n; i++) s[i] = new Spring(0.28f, 0.85f, 0);
        return s;
    }
}
