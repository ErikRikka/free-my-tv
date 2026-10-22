package io.freemytv;

import android.service.voice.VoiceInteractionService;

/** Voice prototype: the TV's assistant slot (the remote's mic button opens a session of this service). */
public class VoiceService extends VoiceInteractionService {
    @Override public void onReady() {
        super.onReady();
        AppLog.w("voice: service ready");
    }
}
