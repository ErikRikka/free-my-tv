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
 * well. The remote works as on any TV app: Up and Down go through the reels, OK pauses, Back leaves. Not signed in, it
 * opens on the sign-in page; off the reels the arrows move a pointer and OK taps under it (fields, buttons, the
 * "I'm not a robot" box).
 */
public class ReelsActivity extends Activity {
    /** Its place on the shelf, which holds packages: a name no app has (HomeApps.launchIntent knows it). */
    static final String SHELF_ID = "io.freemytv.reels";
    private static final String START = "https://www.instagram.com/reels/",
            LOGIN = "https://www.instagram.com/accounts/login/?next=%2Freels%2F";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";

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
            + "document.addEventListener('focusout',function(){setTimeout(function(){FMTV.typing(typing());},0);},true);"
            + "function current(){var vs=[].slice.call(document.querySelectorAll('video')),mid=innerHeight/2,best=null,bd=1e9;"
            + "vs.forEach(function(v){var r=v.getBoundingClientRect();if(r.height<50)return;var dd=Math.abs(r.top+r.height/2-mid);if(dd<bd){bd=dd;best=v;}});return [vs,best];}"
            + "window.fmtvMove=function(dir){var c=current(),vs=c[0].filter(function(v){return v.getBoundingClientRect().height>=50;}),i=vs.indexOf(c[1]);"
            + "var n=vs[i+dir];if(n){n.scrollIntoView({block:'center',behavior:'smooth'});return;}"
            + "var s=document.scrollingElement;(c[1]?c[1].closest('main')||s:s);window.scrollBy({top:dir*innerHeight,behavior:'smooth'});"
            + "var e=document.querySelectorAll('[style*=\"scroll-snap\"],main div');for(var k=0;k<e.length;k++){var el=e[k];if(el.scrollHeight>el.clientHeight+50&&getComputedStyle(el).overflowY!=='visible'){el.scrollBy({top:dir*el.clientHeight,behavior:'smooth'});break;}}};"
            + "window.fmtvToggle=function(){var v=current()[1];if(!v)return;v.muted=false;if(v.paused)v.play();else v.pause();};"
            + "})();";

    private WebView web;
    private View pointer;
    private float px = -1, py = -1;
    private volatile boolean typing;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            WebView.setDataDirectorySuffix("reels");   // its own process: Android wants each to keep its web data apart
        } catch (IllegalStateException ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void typing(boolean on) {
                typing = on;
            }
        }, "FMTV");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                v.evaluateJavascript(SCRIPT, null);
            }

            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(SCRIPT, null);
                CookieManager.getInstance().flush();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                String host = r.getUrl().getHost();
                return host == null || !(host.endsWith("instagram.com") || host.endsWith("facebook.com"));   // stays on Instagram
            }
        });
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(web);
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
        int size = Math.round(getResources().getDisplayMetrics().heightPixels * 0.035f);
        root.addView(pointer, new android.widget.FrameLayout.LayoutParams(size, size));
        pointer.setVisibility(View.GONE);
        setContentView(root);
        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        web.loadUrl(cookies != null && cookies.contains("sessionid=") ? START : LOGIN);   // signed in, or the sign-in page first
    }

    /** On the reels (not typing, not signing in), the remote goes through them. */
    private boolean onReels() {
        String url = web.getUrl();
        return !typing && url != null && url.contains("instagram.com/reel");
    }

    private void movePointer(int key, int repeat) {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        if (px < 0) {
            px = m.widthPixels / 2f;
            py = m.heightPixels / 2f;
        }
        float step = m.heightPixels * (repeat < 3 ? 0.03f : repeat < 10 ? 0.06f : 0.1f);   // faster the longer it's held
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) px -= step;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) px += step;
        if (key == KeyEvent.KEYCODE_DPAD_UP) py -= step;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) py += step;
        px = Math.max(0, Math.min(m.widthPixels - 1, px));
        py = Math.max(0, Math.min(m.heightPixels - 1, py));
        pointer.setTranslationX(px - pointer.getWidth() / 2f);
        pointer.setTranslationY(py - pointer.getHeight() / 2f);
    }

    /** A finger's tap where the pointer is: the page (and a check box inside it) takes it as a touch. */
    private void tap() {
        if (px < 0) movePointer(0, 0);
        long t = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, px, py, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, px, py, 0);
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
        if (onReels() && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                web.evaluateJavascript(SCRIPT, null);
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) web.evaluateJavascript("fmtvMove(1)", null);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) web.evaluateJavascript("fmtvMove(-1)", null);
                else web.evaluateJavascript("fmtvToggle()", null);
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
