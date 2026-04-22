package com.zoro.ugv;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoiceCommandManager
 *
 * Ties together:
 *   AudioRecord  →  MFCCProcessor  →  DTWMatcher  →  BLE send
 *
 * Usage:
 *   manager = new VoiceCommandManager(context, templateStore, gatt, listener);
 *   manager.startListening();   // call from button press
 *   manager.stopListening();    // call on button release (or auto-stops after 1.5 s)
 *   manager.recordTrainingSample("forward", listener);
 */
public class VoiceCommandManager {

    private static final String TAG = "VoiceCmdMgr";

    // BLE UUIDs — must match esp32_side/src/ble_receive.cpp
    private static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID CTRL_CHAR    = UUID.fromString("12345678-1234-1234-1234-123456789002");

    // ── Listener interface ────────────────────────────────────────────────────

    public interface CommandListener {
        /** Called on the main thread with the recognition result. */
        void onResult(DTWMatcher.MatchResult result);
        /** Called on the main thread if something goes wrong. */
        void onError(String message);
    }

    public interface TrainingListener {
        void onSaved(String command, int totalCount);
        void onError(String message);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Context          context;
    private final TemplateStore    store;
    private final MFCCProcessor    mfcc;
    private final DTWMatcher       dtw;
    private final CommandListener  listener;
    private final ExecutorService  executor = Executors.newSingleThreadExecutor();
    private final Handler          mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt              gatt;
    private BluetoothGattCharacteristic ctrlChar;

    private volatile boolean recording = false;
    private AudioRecord audioRecord;

    // ── Constructor ───────────────────────────────────────────────────────────

    public VoiceCommandManager(Context context,
                                TemplateStore store,
                                BluetoothGatt gatt,
                                CommandListener listener) {
        this.context  = context.getApplicationContext();
        this.store    = store;
        this.gatt     = gatt;
        this.listener = listener;
        this.mfcc     = new MFCCProcessor();
        this.dtw      = new DTWMatcher();
        resolveCtrlChar();
    }

    /** Call this if the BLE connection is re-established after construction. */
    public void setGatt(BluetoothGatt gatt) {
        this.gatt = gatt;
        resolveCtrlChar();
    }

    // ── Recording & matching ──────────────────────────────────────────────────

    /**
     * Records exactly 1.5 s of audio, runs MFCC + DTW, sends BLE command if matched.
     * Non-blocking — result delivered via CommandListener on the main thread.
     */
    @SuppressLint("MissingPermission")
    public void startListening() {
        if (recording) return;
        recording = true;
        executor.execute(() -> {
            short[] audio = captureAudio(MFCCProcessor.NUM_SAMPLES);
            if (audio == null) {
                mainHandler.post(() -> listener.onError("AudioRecord failed"));
                recording = false;
                return;
            }
            float[][] features = mfcc.extract(audio);
            DTWMatcher.MatchResult result = dtw.findBestCommand(features, store.getAll());

            Log.d(TAG, result.toString());
            for (String cmd : TemplateStore.COMMANDS) {
                Float s = result.allScores.get(cmd);
                Log.d(TAG, String.format("  %-8s %.2f", cmd, s == null ? 999f : s));
            }

            if (result.matched) sendBleCommand(result.command);
            mainHandler.post(() -> listener.onResult(result));
            recording = false;
        });
    }

    /** Force-stop an in-progress recording (normally not needed — auto-stops at 1.5 s). */
    public void stopListening() {
        recording = false;
        if (audioRecord != null) audioRecord.stop();
    }

    // ── Training mode ─────────────────────────────────────────────────────────

    /**
     * Records a sample and saves it as a new template for the given command.
     * Callback on main thread.
     */
    @SuppressLint("MissingPermission")
    public void recordTrainingSample(String command, TrainingListener cb) {
        if (recording) {
            mainHandler.post(() -> cb.onError("Already recording"));
            return;
        }
        if (store.count(command) >= TemplateStore.MAX_TEMPLATES) {
            mainHandler.post(() -> cb.onError("Already at 10 templates — clear some first"));
            return;
        }
        recording = true;
        executor.execute(() -> {
            short[] audio = captureAudio(MFCCProcessor.NUM_SAMPLES);
            if (audio == null) {
                mainHandler.post(() -> cb.onError("AudioRecord failed"));
                recording = false;
                return;
            }
            float[][] features = mfcc.extract(audio);
            boolean ok = store.add(command, features);
            int count = store.count(command);
            mainHandler.post(() -> {
                if (ok) cb.onSaved(command, count);
                else    cb.onError("Could not save template");
            });
            recording = false;
        });
    }

    // ── Internal audio capture ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private short[] captureAudio(int numSamples) {
        int bufSize = AudioRecord.getMinBufferSize(
                MFCCProcessor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (bufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Bad AudioRecord params");
            return null;
        }
        bufSize = Math.max(bufSize, numSamples * 2);

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MFCCProcessor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            audioRecord.release();
            return null;
        }

        short[] audio = new short[numSamples];
        audioRecord.startRecording();
        int read = 0;
        while (read < numSamples && recording) {
            int n = audioRecord.read(audio, read, numSamples - read);
            if (n > 0) read += n;
        }
        audioRecord.stop();
        audioRecord.release();
        audioRecord = null;

        return (read == numSamples) ? audio : null;
    }

    // ── BLE send ──────────────────────────────────────────────────────────────

    private void sendBleCommand(String command) {
        if (gatt == null || ctrlChar == null) {
            Log.w(TAG, "BLE not ready — command not sent: " + command);
            return;
        }
        ctrlChar.setValue(command.getBytes());
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = gatt.writeCharacteristic(ctrlChar);
        Log.d(TAG, "BLE write '" + command + "' → " + (ok ? "ok" : "failed"));
    }

    private void resolveCtrlChar() {
        if (gatt == null) return;
        BluetoothGattService svc = gatt.getService(SERVICE_UUID);
        if (svc != null) ctrlChar = svc.getCharacteristic(CTRL_CHAR);
    }

    public boolean isRecording() { return recording; }
}
