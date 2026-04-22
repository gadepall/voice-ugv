package com.zoro.ugv;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DTWMatcher — Java port of esp32_side/src/dtw.cpp
 *
 * Uses Best-of-N strategy: for each command, score = min DTW across all stored templates.
 * The command with the lowest score (below THRESHOLD) wins.
 */
public class DTWMatcher {

    public static final float THRESHOLD = 12.0f;

    // ── Public result type ────────────────────────────────────────────────────

    public static class MatchResult {
        /** Matched command label, or null if no match. */
        public final String  command;
        /** Best (lowest) DTW score across all templates for winning command. */
        public final float   score;
        /** True if score < THRESHOLD. */
        public final boolean matched;
        /** Scores for every command — useful for the debug UI. */
        public final Map<String, Float> allScores;

        MatchResult(String command, float score, boolean matched,
                    Map<String, Float> allScores) {
            this.command   = command;
            this.score     = score;
            this.matched   = matched;
            this.allScores = allScores;
        }

        @Override
        public String toString() {
            return matched
                    ? String.format("Match: %s (%.2f)", command, score)
                    : String.format("NoMatch (best=%.2f)", score);
        }
    }

    // ── Core DTW ─────────────────────────────────────────────────────────────

    private float euclidean(float[] a, float[] b) {
        float sum = 0.0f;
        for (int i = 0; i < MFCCProcessor.NUM_COEFFS; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * Memory-optimised DTW with path-length normalisation.
     * Matches esp32_side/src/dtw.cpp dtw_distance() exactly.
     */
    private float dtwDistance(float[][] seq1, float[][] seq2) {
        int len1 = seq1.length;
        int len2 = seq2.length;

        float[] prev     = new float[len2];
        float[] curr     = new float[len2];
        float[] prevPath = new float[len2];
        float[] currPath = new float[len2];

        prev[0]     = euclidean(seq1[0], seq2[0]);
        prevPath[0] = 1.0f;
        for (int j = 1; j < len2; j++) {
            prev[j]     = prev[j - 1] + euclidean(seq1[0], seq2[j]);
            prevPath[j] = prevPath[j - 1] + 1.0f;
        }

        for (int i = 1; i < len1; i++) {
            curr[0]     = prev[0] + euclidean(seq1[i], seq2[0]);
            currPath[0] = prevPath[0] + 1.0f;

            for (int j = 1; j < len2; j++) {
                float cost = euclidean(seq1[i], seq2[j]);
                float diag = prev[j - 1],  up   = prev[j],  left  = curr[j - 1];
                float diagP= prevPath[j-1], upP  = prevPath[j], leftP = currPath[j-1];

                if (diag <= up && diag <= left) {
                    curr[j] = cost + diag;  currPath[j] = diagP + 1.0f;
                } else if (left <= up) {
                    curr[j] = cost + left;  currPath[j] = leftP + 1.0f;
                } else {
                    curr[j] = cost + up;    currPath[j] = upP   + 1.0f;
                }
            }

            // swap rows
            float[] tmp; tmp = prev; prev = curr; curr = tmp;
                         tmp = prevPath; prevPath = currPath; currPath = tmp;
        }
        return prev[len2 - 1] / prevPath[len2 - 1];
    }

    // ── Best-of-N ─────────────────────────────────────────────────────────────

    /**
     * Returns the lowest DTW score across all templates for one command.
     * With 10 templates this is the "best-of-10" strategy.
     */
    public float bestScore(float[][] query, List<float[][]> templates) {
        float best = Float.MAX_VALUE;
        for (float[][] tmpl : templates) {
            float s = dtwDistance(query, tmpl);
            if (s < best) best = s;
        }
        return best;
    }

    /**
     * Compares query against all commands and returns a MatchResult.
     *
     * @param query        MFCC feature matrix from MFCCProcessor.extract()
     * @param allTemplates map of command → list of up to 10 templates
     */
    public MatchResult findBestCommand(float[][] query,
                                       Map<String, List<float[][]>> allTemplates) {
        Map<String, Float> scores = new LinkedHashMap<>();
        float  bestScore   = THRESHOLD;   // only accept scores below threshold
        String bestCommand = null;

        for (Map.Entry<String, List<float[][]>> entry : allTemplates.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            float s = bestScore(query, entry.getValue());
            scores.put(entry.getKey(), s);
            if (s < bestScore) {
                bestScore   = s;
                bestCommand = entry.getKey();
            }
        }

        // Fill in commands with no templates so the debug UI always has all 5
        for (String cmd : TemplateStore.COMMANDS) {
            scores.putIfAbsent(cmd, Float.MAX_VALUE);
        }

        return new MatchResult(bestCommand, bestScore, bestCommand != null, scores);
    }
}
