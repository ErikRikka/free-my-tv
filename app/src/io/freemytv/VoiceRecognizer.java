package io.freemytv;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** The assistant slot asks for a recognition service; this one recognizes nothing (the prototype listens itself). */
public class VoiceRecognizer extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.error(SpeechRecognizer.ERROR_NO_MATCH);
        } catch (android.os.RemoteException ignored) {
        }
    }

    @Override protected void onCancel(Callback callback) {}

    @Override protected void onStopListening(Callback callback) {}
}
