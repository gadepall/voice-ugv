package com.zoro.ugv;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private static final String ESP32_MAC = "EC:94:CB:4A:6E:9E";
    private static final int    PERM_CODE = 101;

    // Static so DebugActivity can access it via MainActivity.getGatt()
    private static BluetoothGatt gatt;
    public static BluetoothGatt getGatt() { return gatt; }

    private VoiceCommandManager vcm;
    private TemplateStore       store;
    private TextView            statusText;
    private Button              speakButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText  = findViewById(R.id.statusText);
        speakButton = findViewById(R.id.speakButton);

        store = new TemplateStore(this);
        requestPermissionsIfNeeded();

        // Debug button → opens DebugActivity
        findViewById(R.id.debugButton).setOnClickListener(v ->
                startActivity(new Intent(this, DebugActivity.class)));

        // Speak button — hold to record
        speakButton.setOnTouchListener((v, event) -> {
            if (vcm == null) {
                statusText.setText("Not connected to ESP32");
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                speakButton.setText("Listening…");
                statusText.setText("Speak now");
                vcm.startListening();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                speakButton.setText("Hold to speak");
            }
            return false;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gatt != null) gatt.close();
    }

    private void connectBle() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            statusText.setText("Bluetooth is off");
            return;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(ESP32_MAC);
        } catch (IllegalArgumentException e) {
            statusText.setText("Invalid MAC address");
            return;
        }

        statusText.setText("Connecting to ESP32…");

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices();
                    runOnUiThread(() -> statusText.setText("Connected — discovering services…"));
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected — tap Connect");
                        speakButton.setEnabled(false);
                    });
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                runOnUiThread(() -> {
                    vcm = new VoiceCommandManager(
                            MainActivity.this,
                            store,
                            g,
                            new VoiceCommandManager.CommandListener() {
                                @Override
                                public void onResult(DTWMatcher.MatchResult result) {
                                    speakButton.setText("Hold to speak");
                                    if (result.matched) {
                                        statusText.setText("Sent: " + result.command.toUpperCase()
                                                + String.format("  (%.2f)", result.score));
                                    } else {
                                        statusText.setText("No match — try again");
                                    }
                                }
                                @Override
                                public void onError(String msg) {
                                    speakButton.setText("Hold to speak");
                                    statusText.setText("Error: " + msg);
                                }
                            });
                    speakButton.setEnabled(true);
                    statusText.setText("Ready — hold button and speak");
                });
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        ActivityCompat.requestPermissions(this, perms, PERM_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                connectBle();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
            }
        }
    }
}
