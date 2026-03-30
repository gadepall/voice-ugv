package com.zoro.ugv;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TemplateStore
 *
 * Manages up to MAX_TEMPLATES per command.
 *
 * Sources (loaded in order):
 *   1. assets/templates/{cmd}_0.bin  — original template from templates.h
 *   2. files/templates/{cmd}_1.bin … {cmd}_9.bin — recorded in-app
 *
 * Binary format per file:
 *   int32   numFrames  (little-endian)
 *   int32   numCoeffs
 *   float32 data[numFrames * numCoeffs]  row-major
 */
public class TemplateStore {

    private static final String TAG          = "TemplateStore";
    public  static final int    MAX_TEMPLATES = 10;
    public  static final String[] COMMANDS   =
            {"forward", "back", "left", "right", "stop"};

    private final Context context;
    private final Map<String, List<float[][]>> templates = new LinkedHashMap<>();

    public TemplateStore(Context context) {
        this.context = context.getApplicationContext();
        for (String cmd : COMMANDS) templates.put(cmd, new ArrayList<>());
        loadAll();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Map<String, List<float[][]>> getAll() {
        return Collections.unmodifiableMap(templates);
    }

    public List<float[][]> get(String command) {
        List<float[][]> list = templates.get(command);
        return list != null ? list : Collections.emptyList();
    }

    public int count(String command) {
        List<float[][]> list = templates.get(command);
        return list == null ? 0 : list.size();
    }

    /**
     * Add a newly recorded template for a command.
     * Persisted immediately to internal storage.
     * Returns false if already at MAX_TEMPLATES.
     */
    public boolean add(String command, float[][] mfcc) {
        List<float[][]> list = templates.get(command);
        if (list == null || list.size() >= MAX_TEMPLATES) return false;

        int index = list.size();
        list.add(mfcc);
        saveToDisk(command, index, mfcc);
        Log.d(TAG, "Saved template " + command + "_" + index);
        return true;
    }

    /**
     * Delete all recorded (index ≥ 1) templates for a command,
     * keeping the original asset template.
     */
    public void clearRecorded(String command) {
        List<float[][]> list = templates.get(command);
        if (list == null) return;

        // keep only index 0 (asset template)
        while (list.size() > 1) list.remove(list.size() - 1);

        // delete files
        File dir = getInternalDir();
        for (int i = 1; i < MAX_TEMPLATES; i++) {
            new File(dir, command + "_" + i + ".bin").delete();
        }
        Log.d(TAG, "Cleared recorded templates for " + command);
    }

    /** Delete ALL recorded templates for ALL commands. */
    public void clearAllRecorded() {
        for (String cmd : COMMANDS) clearRecorded(cmd);
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private void loadAll() {
        for (String cmd : COMMANDS) {
            List<float[][]> list = templates.get(cmd);

            // 1. Asset template (index 0)
            float[][] asset = loadFromAssets(cmd, 0);
            if (asset != null) {
                list.add(asset);
                Log.d(TAG, "Loaded asset " + cmd + "_0  (" + asset.length + " frames)");
            } else {
                Log.w(TAG, "No asset template for " + cmd);
            }

            // 2. Recorded templates (index 1..9)
            File dir = getInternalDir();
            for (int i = 1; i < MAX_TEMPLATES; i++) {
                File f = new File(dir, cmd + "_" + i + ".bin");
                if (!f.exists()) continue;
                float[][] tmpl = loadFromFile(f);
                if (tmpl != null) {
                    list.add(tmpl);
                    Log.d(TAG, "Loaded recorded " + cmd + "_" + i
                            + "  (" + tmpl.length + " frames)");
                }
            }
        }
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private float[][] loadFromAssets(String command, int index) {
        String path = "templates/" + command + "_" + index + ".bin";
        try (InputStream is = context.getAssets().open(path)) {
            return readBin(is);
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + path);
            return null;
        }
    }

    private float[][] loadFromFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readBin(fis);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private void saveToDisk(String command, int index, float[][] mfcc) {
        File dir = getInternalDir();
        dir.mkdirs();
        File file = new File(dir, command + "_" + index + ".bin");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            writeBin(fos, mfcc);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save template: " + e.getMessage());
        }
    }

    /**
     * Read template binary: int32 numFrames, int32 numCoeffs, float32[] data
     * Little-endian throughout (matches convert_templates.py output).
     */
    private float[][] readBin(InputStream is) throws IOException {
        byte[] header = new byte[8];
        if (is.read(header) < 8) throw new IOException("Header too short");
        ByteBuffer hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int numFrames = hdr.getInt();
        int numCoeffs = hdr.getInt();

        int total = numFrames * numCoeffs;
        byte[] raw = new byte[total * 4];
        int read = 0;
        while (read < raw.length) {
            int n = is.read(raw, read, raw.length - read);
            if (n < 0) throw new IOException("Unexpected EOF");
            read += n;
        }

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[][] result = new float[numFrames][numCoeffs];
        for (int f = 0; f < numFrames; f++)
            for (int c = 0; c < numCoeffs; c++)
                result[f][c] = buf.getFloat();
        return result;
    }

    private void writeBin(FileOutputStream fos, float[][] mfcc) throws IOException {
        int numFrames = mfcc.length;
        int numCoeffs = MFCCProcessor.NUM_COEFFS;
        ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(numFrames);
        hdr.putInt(numCoeffs);
        fos.write(hdr.array());

        ByteBuffer buf = ByteBuffer.allocate(numFrames * numCoeffs * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float[] frame : mfcc)
            for (float v : frame) buf.putFloat(v);
        fos.write(buf.array());
    }

    private File getInternalDir() {
        return new File(context.getFilesDir(), "templates");
    }
}
