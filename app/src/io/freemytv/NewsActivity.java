package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** The news feed, one story to a screen. (A first stand-in: shows which story it was opened on.) */
public class NewsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setTextSize(32);
        t.setText("News: " + getIntent().getStringExtra("story"));
        setContentView(t);
    }
}
