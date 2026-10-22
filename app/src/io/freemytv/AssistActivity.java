package io.freemytv;

import android.app.Activity;
import android.os.Bundle;

/** Android's assistant role asks for an activity that answers ACTION_ASSIST; the real work is VoiceService's session. */
public class AssistActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        finish();
    }
}
