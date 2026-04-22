package com.zoro.ugv;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.LinkedHashMap;
import java.util.Map;

public class DebugActivity extends AppCompatActivity {

    private static final float MAX_SCORE  = 15.0f;
    private static final float THRESHOLD  = DTWMatcher.THRESHOLD;
    private static final int   COLOR_GOOD = 0xFF2ECC71;
    private static final int   COLOR_WARN = 0xFFF39C12;
    private static final int   COLOR_BAD  = 0xFFE74C3C;
    private static final int   COLOR_WIN  = 0xFF3498DB;

    private TemplateStore       store;
    private MFCCProcessor       mfcc;
    private DTWMatcher          dtw;
    private VoiceCommandManager vcm;

    private final Map<String, ProgressBar> scoreBars   = new LinkedHashMap<>();
    private final Map<String, TextView>    scoreLabels = new LinkedHashMap<>();
    private final Map<String, TextView>    countBadges = new LinkedHashMap<>();

    private TextView statusText;
    private Button   testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new TemplateStore(this);
        mfcc  = new MFCCProcessor();
        dtw   = new DTWMatcher();

        // Uses MainActivity's gatt so motors work from Debug screen
        vcm = new VoiceCommandManager(this, store, MainActivity.getGatt(),
                new VoiceCommandManager.CommandListener() {
                    @Override public void onResult(DTWMatcher.MatchResult result) { updateScores(result); }
                    @Override public void onError(String msg) { statusText.setText("Error: " + msg); }
                });

        setContentView(buildLayout());
        refreshCounts();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vcm.isRecording()) vcm.stopListening();
    }

    @SuppressLint("ClickableViewAccessibility")
    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);

        root.addView(sectionHeader("Live DTW scores"));

        statusText = new TextView(this);
        statusText.setText("Hold the button and speak a command");
        statusText.setTextSize(14f);
        statusText.setPadding(0, 0, 0, dp(8));
        root.addView(statusText);

        for (String cmd : TemplateStore.COMMANDS) {
            root.addView(buildScoreRow(cmd));
        }

        testButton = new Button(this);
        testButton.setText("Hold to test");
        testButton.setBackgroundColor(0xFF2980B9);
        testButton.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(12);
        testButton.setLayoutParams(lp);

        testButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                testButton.setText("Recording…");
                testButton.setBackgroundColor(0xFFE74C3C);
                vcm.startListening();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                testButton.setText("Hold to test");
                testButton.setBackgroundColor(0xFF2980B9);
                vcm.stopListening();
            }
            return false;
        });
        root.addView(testButton);

        root.addView(sectionHeader("Training  (max 10 per command)"));

        for (String cmd : TemplateStore.COMMANDS) {
            root.addView(buildTrainingRow(cmd));
        }

        Button clearBtn = new Button(this);
        clearBtn.setText("Clear all recorded samples");
        clearBtn.setBackgroundColor(0xFF7F8C8D);
        clearBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        clp.topMargin = dp(16);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            store.clearAllRecorded();
            refreshCounts();
            Toast.makeText(this, "All recorded samples cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearBtn);

        return scroll;
    }

    private View buildScoreRow(String cmd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(6);
        row.setLayoutParams(rp);

        TextView label = new TextView(this);
        label.setText(cmd);
        label.setTextSize(13f);
        label.setMinWidth(dp(68));

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(18), 1f);
        bp.leftMargin  = dp(8);
        bp.rightMargin = dp(8);
        bar.setLayoutParams(bp);

        TextView scoreLabel = new TextView(this);
        scoreLabel.setText("—");
        scoreLabel.setTextSize(12f);
        scoreLabel.setMinWidth(dp(44));
        scoreLabel.setGravity(Gravity.END);

        row.addView(label);
        row.addView(bar);
        row.addView(scoreLabel);

        scoreBars.put(cmd, bar);
        scoreLabels.put(cmd, scoreLabel);
        return row;
    }

    private View buildTrainingRow(String cmd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(cmd);
        nameLabel.setTextSize(14f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLabel.setLayoutParams(nlp);

        TextView badge = new TextView(this);
        badge.setTextSize(12f);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setBackgroundColor(0xFF34495E);
        badge.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = dp(8);
        badge.setLayoutParams(badgeLp);
        countBadges.put(cmd, badge);

        Button recBtn = new Button(this);
        recBtn.setText("Record");
        recBtn.setTextSize(12f);
        recBtn.setBackgroundColor(0xFF27AE60);
        recBtn.setTextColor(Color.WHITE);
        recBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(90), dp(40)));

        recBtn.setOnClickListener(v -> {
            recBtn.setText("…");
            recBtn.setEnabled(false);
            vcm.recordTrainingSample(cmd, new VoiceCommandManager.TrainingListener() {
                @Override
                public void onSaved(String command, int totalCount) {
                    recBtn.setText("Record");
                    recBtn.setEnabled(true);
                    refreshCounts();
                    Toast.makeText(DebugActivity.this,
                            "Saved! " + command + " now has " + totalCount + " template(s)",
                            Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onError(String message) {
                    recBtn.setText("Record");
                    recBtn.setEnabled(true);
                    Toast.makeText(DebugActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        });

        row.addView(nameLabel);
        row.addView(badge);
        row.addView(recBtn);
        return row;
    }

    private TextView sectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title.toUpperCase());
        tv.setTextSize(11f);
        tv.setLetterSpacing(0.1f);
        tv.setTextColor(0xFF7F8C8D);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin    = dp(20);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateScores(DTWMatcher.MatchResult result) {
        if (result.matched) {
            statusText.setText("Matched: " + result.command.toUpperCase()
                    + String.format("  (%.2f)", result.score));
        } else {
            statusText.setText("No match" + String.format("  (best=%.2f)", result.score));
        }

        for (String cmd : TemplateStore.COMMANDS) {
            Float s = result.allScores.get(cmd);
            if (s == null || s == Float.MAX_VALUE) continue;

            ProgressBar bar   = scoreBars.get(cmd);
            TextView    label = scoreLabels.get(cmd);
            if (bar == null || label == null) continue;

            float clamped = Math.min(s, MAX_SCORE);
            int progress = (int)(clamped / MAX_SCORE * 1000);
            bar.setProgress(progress);
            label.setText(String.format("%.2f", s));

            int color;
            if (result.matched && cmd.equals(result.command)) {
                color = COLOR_WIN;
            } else if (s < THRESHOLD) {
                color = COLOR_GOOD;
            } else if (s < THRESHOLD * 1.5f) {
                color = COLOR_WARN;
            } else {
                color = COLOR_BAD;
            }
            bar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void refreshCounts() {
        for (String cmd : TemplateStore.COMMANDS) {
            TextView badge = countBadges.get(cmd);
            if (badge != null) {
                int n = store.count(cmd);
                badge.setText(n + "/" + TemplateStore.MAX_TEMPLATES);
                badge.setBackgroundColor(n >= TemplateStore.MAX_TEMPLATES ? 0xFFE74C3C : 0xFF34495E);
            }
        }
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }
}
