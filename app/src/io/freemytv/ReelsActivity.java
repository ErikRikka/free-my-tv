package io.freemytv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Instagram's Reels on the TV, through its website. The phone apps play reels with the TV's VP9 decoder, which turns many
 * of them into smears; here the page is told the TV can't play VP9, AV1 or HEVC, so Instagram sends H.264, which it plays
 * well. The remote works as on any TV app: Up and Down go through the reels, Right likes (held: saves), OK pauses (twice:
 * the reels go on by themselves), Back leaves; the assistant (the mic button) knows which reel is on screen. Not signed in, it
 * opens on the sign-in page; off the reels the arrows move a pointer and OK taps under it (fields, buttons, the
 * "I'm not a robot" box).
 */
public class ReelsActivity extends Activity {
    /** Its place on the shelf, which holds packages: a name no app has (HomeApps.launchIntent knows it). */
    static final String SHELF_ID = "io.freemytv.reels";
    private static final String START = "https://www.instagram.com/reels/",
            LOGIN = "https://www.instagram.com/accounts/login/?next=%2Freels%2F";

    /** Runs in every page: hides the codecs the TV plays badly, turns the sound on, and moves between reels. */
    private static final String SCRIPT = "(function(){if(window.__fmtv)return;window.__fmtv=1;"
            + "var bad=/vp0?9|av01|hev1|hvc1/i;"
            + "if(window.MediaSource){var t=MediaSource.isTypeSupported.bind(MediaSource);MediaSource.isTypeSupported=function(c){return bad.test(c)?false:t(c);};}"
            + "var p=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(c){return bad.test(c)?'':p.call(this,c);};"
            + "if(navigator.mediaCapabilities){var d=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);"
            + "navigator.mediaCapabilities.decodingInfo=function(c){var ct=(c&&c.video&&c.video.contentType)||'';"
            + "return bad.test(ct)?Promise.resolve({supported:false,smooth:false,powerEfficient:false}):d(c);};}"
            + "document.addEventListener('play',function(e){var v=e.target;if(v.tagName==='VIDEO'){v.muted=false;v.volume=1;}},true);"
            // Instagram mutes what plays by itself and keeps it that way: the sound goes back on every second.
            + "setInterval(function(){[].forEach.call(document.querySelectorAll('video'),function(v){if(!v.paused&&v.muted){v.muted=false;v.volume=1;}});},1000);"
            + "function typing(){var a=document.activeElement;return !!a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable);}"
            + "document.addEventListener('focusin',function(){FMTV.typing(typing());},true);"
            + "setInterval(function(){FMTV.dialog(!!document.querySelector('[role=dialog]'));},400);"   // a question over the reels
            + "document.addEventListener('focusout',function(){setTimeout(function(){FMTV.typing(typing());},0);},true);"
            + "function current(){var vs=[].slice.call(document.querySelectorAll('video')),mid=innerHeight/2,best=null,bd=1e9;"
            + "vs.forEach(function(v){var r=v.getBoundingClientRect();if(r.height<50)return;var dd=Math.abs(r.top+r.height/2-mid);if(dd<bd){bd=dd;best=v;}});return [vs,best];}"
            // Right: the current reel's heart, only if it isn't liked yet (pressing again doesn't take the like back).
            + "window.fmtvLike=function(){var v=current()[1];if(!v)return 'none';for(var el=v;el;el=el.parentElement){"
            + "if(el.querySelector('svg[aria-label=\\\"Unlike\\\"]'))return 'already';"
            + "var h=el.querySelector('svg[aria-label=\\\"Like\\\"]');if(h){(h.closest('[role=button],button')||h).click();return 'liked';}}return 'none';};"
            // Long Right: into Saved, the way the site's own bookmark does it (its media id from the reel's code in the address).
            + "window.fmtvSave=function(){var m=location.pathname.match(/\\/reels?\\/([\\w-]+)/);if(!m)return Promise.resolve('none');"
            + "var a='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_',id=BigInt(0);"
            + "for(var i=0;i<m[1].length;i++)id=id*BigInt(64)+BigInt(a.indexOf(m[1][i]));"
            + "var t=(document.cookie.match(/csrftoken=([^;]+)/)||[])[1]||'';"
            + "return fetch('/api/v1/web/save/'+id+'/save/',{method:'POST',credentials:'include',headers:{'x-csrftoken':t,"
            + "'x-ig-app-id':'936619743392459','x-requested-with':'XMLHttpRequest','content-type':'application/x-www-form-urlencoded'}})"
            + ".then(function(r){return r.ok?'saved':'error '+r.status;},function(e){return 'error';});};"
            // Double OK: the next reel when this one ends (they loop on the site, so near the end rather than 'ended').
            + "window.fmtvAuto=false;document.addEventListener('timeupdate',function(e){var v=e.target;if(!fmtvAuto||v!==current()[1])return;"
            + "if(v.currentTime<1)v.__fmtvNext=0;if(v.duration&&v.currentTime>v.duration-0.4&&!v.__fmtvNext){v.__fmtvNext=1;FMTV.next();}},true);"
            // How far the reel is, and where its bottom edge is (the bar is drawn there, the site has none on phones).
            + "setInterval(function(){var v=current()[1];if(!v||!v.duration)return FMTV.progress(-1,0);var r=v.getBoundingClientRect();"
            // Its tab bar: up from the Home icon to the strip as wide as the page.
            + "var b=Math.min(r.bottom,innerHeight),n=document.querySelector('svg[aria-label=\\\"Home\\\"]');"
            + "while(n&&n.getBoundingClientRect().width<innerWidth*0.9)n=n.parentElement;"
            + "if(n){var t=n.getBoundingClientRect().top;if(t>innerHeight*0.6&&t<b)b=t;}"
            + "FMTV.progress(v.currentTime/v.duration,b/innerHeight);},200);"
            // The TV has five video decoders and the site keeps hold of them; when a reel can't get one ("trouble playing"),
            // the page is opened again on that same reel, which lets them all go.
            + "setInterval(function(){var v=current()[1];"
            + "if((v&&v.error)||/trouble playing this video/i.test(document.body.innerText||''))FMTV.reload(location.href);},1000);"
            // What's on screen, for the assistant: author, music, caption.
            + "setInterval(function(){var v=current()[1];if(!v)return;var el=v.parentElement;FMTV.reel(location.href,(el.innerText||'').slice(0,600));},2000);"
            // The next (or previous) reel put in place at once: the site starts loading it straight away, about a second
            // sooner than after a swipe's scrolling settles. 'drag' when it isn't on the page yet (a swipe loads more).
            + "window.fmtvJump=function(dir){var vs=[].slice.call(document.querySelectorAll('video')).filter(function(v){return v.getBoundingClientRect().height>=50;})"
            + ".sort(function(a,b){return a.getBoundingClientRect().top-b.getBoundingClientRect().top;}),c=current()[1],n=vs[vs.indexOf(c)+dir];"
            + "if(!c||!n)return 'drag';var sc=c;while(sc&&!(sc.scrollHeight>sc.clientHeight+10&&/(auto|scroll)/.test(getComputedStyle(sc).overflowY)))sc=sc.parentElement;"
            + "(sc||document.scrollingElement).scrollBy({top:n.getBoundingClientRect().top-c.getBoundingClientRect().top,behavior:'instant'});return 'ok';};"
            + "window.fmtvToggle=function(){var v=current()[1];if(!v)return;v.muted=false;if(v.paused)v.play();else v.pause();};"
            + "})();";

    private WebView web;
    private View pointer;
    private android.widget.TextView heart, note;
    private View bar;
    private float barDone;
    private float px = -1, py = -1;
    private volatile boolean typing, dialog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            WebView.setDataDirectorySuffix("reels");   // its own process: Android wants each to keep its web data apart
        } catch (IllegalStateException ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A phone's shape in the middle of the screen (Instagram's site for phones, which this WebView is, fits a reel
        // into it whole), drawn a little larger than a phone would so the page is about 400 points wide.
        android.content.res.Configuration phone = new android.content.res.Configuration(getResources().getConfiguration());
        phone.densityDpi = 240;
        phone.uiMode = (phone.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        // A dark theme around it: the page then asks for Instagram's dark look (prefers-color-scheme).
        web = new WebView(new android.view.ContextThemeWrapper(createConfigurationContext(phone), android.R.style.Theme_Black_NoTitleBar));
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The WebView's own name, as it is: a borrowed desktop one made the sign-in's robot check endless.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void typing(boolean on) {
                typing = on;
            }

            @JavascriptInterface public void dialog(boolean on) {
                dialog = on;
            }

            @JavascriptInterface public void reload(String url) {
                long now = System.currentTimeMillis();
                if (now - reloadedAt < 8000) return;
                reloadedAt = now;
                ui.post(() -> web.loadUrl(url));
            }

            @JavascriptInterface public void next() {
                ui.post(() -> go(1));
            }

            @JavascriptInterface public void progress(float done, float bottom) {
                ui.post(() -> showProgress(done, bottom));
            }

            @JavascriptInterface public void reel(String url, String text) {
                showing(url, text);
            }
        }, "FMTV");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                v.evaluateJavascript(SCRIPT, null);
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (url.startsWith("https://")) retries = 0;
                v.evaluateJavascript(SCRIPT, null);
                CookieManager.getInstance().flush();
            }

            // A page that didn't load (QUIC, Chromium's UDP way, gets cut off on some networks): again in a moment;
            // after one failure Chromium goes over plain TCP.
            @Override public void onReceivedError(WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceError err) {
                if (!r.isForMainFrame() || retries >= 5) return;
                retries++;
                String url = r.getUrl().toString();
                ui.postDelayed(() -> v.loadUrl(url), 1500L * retries);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                String host = r.getUrl().getHost();
                return host == null || !(host.endsWith("instagram.com") || host.endsWith("facebook.com"));   // stays on Instagram
            }
        });
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(Math.round(dm.heightPixels * 9f / 16), dm.heightPixels,
                android.view.Gravity.CENTER_HORIZONTAL));
        pointer = new View(this) {   // off the reels (signing in, a check box): arrows move it, OK taps under it
            final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                    ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(android.graphics.Canvas c) {
                float r = getWidth() / 2f;
                fill.setColor(0xCCFFFFFF);
                ring.setColor(0xFF1E1E1E);
                ring.setStyle(android.graphics.Paint.Style.STROKE);
                ring.setStrokeWidth(r * 0.22f);
                c.drawCircle(r, r, r * 0.7f, fill);
                c.drawCircle(r, r, r * 0.7f, ring);
            }
        };
        heart = new android.widget.TextView(this);
        heart.setText("♥");
        heart.setTextColor(0xFFFF3040);
        heart.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dm.heightPixels * 0.22f);
        heart.setShadowLayer(dm.heightPixels * 0.02f, 0, 0, 0x80000000);
        heart.setAlpha(0f);
        root.addView(heart, new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        bar = new View(this) {
            final android.graphics.Paint p = new android.graphics.Paint();

            @Override protected void onDraw(android.graphics.Canvas c) {
                p.setColor(0x55FFFFFF);
                c.drawRect(0, 0, getWidth(), getHeight(), p);
                p.setColor(0xFFFFFFFF);
                c.drawRect(0, 0, getWidth() * barDone, getHeight(), p);
            }
        };
        bar.setVisibility(View.GONE);
        root.addView(bar, new android.widget.FrameLayout.LayoutParams(1, 1, android.view.Gravity.TOP | android.view.Gravity.START));
        note = new android.widget.TextView(this);
        note.setTextColor(Color.WHITE);
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dm.heightPixels * 0.035f);
        note.setPadding(dm.heightPixels / 40, dm.heightPixels / 80, dm.heightPixels / 40, dm.heightPixels / 80);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xCC1E1E22);
        pill.setCornerRadius(dm.heightPixels * 0.05f);
        note.setBackground(pill);
        note.setAlpha(0f);
        android.widget.FrameLayout.LayoutParams np = new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        np.topMargin = dm.heightPixels / 12;
        root.addView(note, np);
        int size = Math.round(getResources().getDisplayMetrics().heightPixels * 0.035f);
        root.addView(pointer, new android.widget.FrameLayout.LayoutParams(size, size));
        pointer.setVisibility(View.GONE);
        setContentView(root);
        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        web.loadUrl(cookies != null && cookies.contains("sessionid=") ? START : LOGIN);   // signed in, or the sign-in page first
    }

    /** On the reels (not typing, no question over them, not signing in), the remote goes through them. */
    private boolean onReels() {
        String url = web.getUrl();
        return !typing && !dialog && url != null && url.contains("instagram.com/reel");
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pause = () -> web.evaluateJavascript("fmtvToggle()", null);
    private boolean auto;
    private long okAt;
    private int retries;
    private volatile long reloadedAt;

    /** A line on screen for a moment. */
    private void say(String text) {
        note.setText(text);
        note.animate().cancel();
        note.setAlpha(1f);
        note.animate().alpha(0f).setStartDelay(1600).setDuration(400).start();
    }

    // MARK: - What's on screen, for the assistant (Live.session reads it): a file, since it lives in another process.

    private long shownAt;

    private void showing(String url, String text) {
        long now = System.currentTimeMillis();
        if (now - shownAt < 1500) return;
        shownAt = now;
        try {
            org.json.JSONObject o = new org.json.JSONObject().put("url", url).put("text", text).put("at", now);
            java.nio.file.Files.write(new java.io.File(getFilesDir(), "reel-now.json").toPath(),
                    o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** The reel on screen right now, in a few words for the assistant's instructions; empty if Reels isn't open. */
    static String nowShowing(android.content.Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "reel-now.json");
            org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (System.currentTimeMillis() - o.optLong("at") > 8000) return "";
            return " Сейчас на экране ролик Instagram Reels (" + o.optString("url") + "). Его текст на экране (автор, музыка, подпись, "
                    + "счётчики): " + o.optString("text").replace('\n', ' ') + ". Если спрашивают про «этот ролик», «эту песню», "
                    + "«кто это», отвечай про него; песню называй по строке с музыкой, при сомнении поищи через web_search.";
        } catch (Exception e) {
            return "";
        }
    }

    /** The thin bar along the reel's bottom edge: white for what's been played. */
    private void showProgress(float done, float bottom) {
        if (done < 0 || !onReels()) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        int h = Math.max(3, Math.round(getResources().getDisplayMetrics().heightPixels * 0.004f));
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.width = web.getWidth();
        lp.height = h;
        lp.leftMargin = web.getLeft();
        lp.topMargin = Math.round(web.getTop() + web.getHeight() * bottom) - h;
        bar.setLayoutParams(lp);
        barDone = Math.max(0, Math.min(1, done));
        bar.invalidate();
    }

    /** A big heart over the reel for a moment: the like went through. */
    private void showHeart() {
        heart.setScaleX(0.6f);
        heart.setScaleY(0.6f);
        heart.setAlpha(1f);
        heart.animate().scaleX(1.15f).scaleY(1.15f).setDuration(220).withEndAction(() ->
                heart.animate().alpha(0f).setStartDelay(350).setDuration(300).start()).start();
    }

    private void movePointer(int key, int repeat) {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        if (px < 0) {
            px = m.widthPixels / 2f;
            py = m.heightPixels / 2f;
        }
        float step = m.heightPixels * (repeat < 3 ? 0.03f : repeat < 10 ? 0.06f : 0.1f);   // faster the longer it's held
        if ((key == KeyEvent.KEYCODE_DPAD_DOWN && py >= m.heightPixels - 1) || (key == KeyEvent.KEYCODE_DPAD_UP && py <= 0)) {
            drag(px, key == KeyEvent.KEYCODE_DPAD_DOWN ? m.heightPixels * 0.3f : -m.heightPixels * 0.3f);   // at the edge: the page moves
            return;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) px -= step;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) px += step;
        if (key == KeyEvent.KEYCODE_DPAD_UP) py -= step;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) py += step;
        px = Math.max(0, Math.min(m.widthPixels - 1, px));
        py = Math.max(0, Math.min(m.heightPixels - 1, py));
        pointer.setTranslationX(px - pointer.getWidth() / 2f);
        pointer.setTranslationY(py - pointer.getHeight() / 2f);
    }

    /** To the next reel (1) or the one before (-1): at once if it's on the page, else a finger's swipe, which loads more. */
    private void go(int dir) {
        web.evaluateJavascript("fmtvJump(" + dir + ")", r -> {
            if (r.contains("drag")) {
                android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                drag(m.widthPixels / 2f, dir * m.heightPixels * 0.6f);
            }
        });
    }

    /** A finger dragging the page up by dy (down for a negative one), in real time, a step a frame, as a finger would. */
    private void drag(float x, float dy) {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        float y0 = m.heightPixels / 2f + dy / 2, y1 = m.heightPixels / 2f - dy / 2, wx = x - web.getLeft();
        long start = android.os.SystemClock.uptimeMillis();
        int steps = 10;
        for (int i = 0; i <= steps + 1; i++) {
            final int n = i;
            ui.postAtTime(() -> {
                int action = n == 0 ? android.view.MotionEvent.ACTION_DOWN : n > steps ? android.view.MotionEvent.ACTION_UP
                        : android.view.MotionEvent.ACTION_MOVE;
                float y = y0 + (y1 - y0) * Math.min(n, steps) / steps;
                android.view.MotionEvent ev = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, wx, y, 0);
                ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                web.dispatchTouchEvent(ev);
                ev.recycle();
            }, start + i * 16L);
        }
    }

    /** A finger's tap where the pointer is: the page (and a check box inside it) takes it as a touch. */
    private void tap() {
        if (px < 0) movePointer(0, 0);
        long t = android.os.SystemClock.uptimeMillis();
        float x = px - web.getLeft();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, py, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, x, py, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (onReels()) pointer.setVisibility(View.GONE);
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if (onReels() && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_RIGHT || ok)) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (down && e.getRepeatCount() == 0) web.evaluateJavascript(SCRIPT, null);
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && down && e.getRepeatCount() == 0) go(1);
            else if (k == KeyEvent.KEYCODE_DPAD_UP && down && e.getRepeatCount() == 0) go(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && !down) {
                // Held (TCL's remote sends no repeats, so by how long): into Saved; a press: a like.
                if (e.getEventTime() - e.getDownTime() > 600) {
                    web.evaluateJavascript("fmtvSave()", r -> say(r.contains("saved") ? "Сохранено" : "Не получилось сохранить"));
                } else {
                    web.evaluateJavascript("fmtvLike()", r -> {
                        if (!r.contains("none")) showHeart();
                    });
                }
            } else if (ok && down && e.getRepeatCount() == 0) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - okAt < 350) {   // a second OK soon after the first: reels go on by themselves, or stop
                    okAt = 0;
                    ui.removeCallbacks(pause);
                    auto = !auto;
                    web.evaluateJavascript("fmtvAuto=" + auto, null);
                    say(auto ? "Автолистание включено" : "Автолистание выключено");
                } else {
                    okAt = now;
                    ui.postDelayed(pause, 350);
                }
            }
            return true;
        }
        boolean arrow = k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!onReels() && (arrow || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
            pointer.setVisibility(View.VISIBLE);
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (arrow) movePointer(k, e.getRepeatCount());
                else if (e.getRepeatCount() == 0) tap();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && !onReels() && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
