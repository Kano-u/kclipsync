package dev.kano.kclipsync;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/** Material 3 status and settings screen. */
public class MainActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceState;
    private TextView serviceDetail;
    private TextView networkName;
    private TextView networkPort;
    private LinearLayout deviceList;
    private EditText inputPort;
    private EditText inputMaxBytes;
    private TextView rootState;
    private TextView xposedState;
    private TextView logText;
    private MaterialButton toggleService;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render(StatusStore.read(MainActivity.this));
            logText.setText(LogStore.text());
            handler.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogStore.init(this);
        setContentView(R.layout.activity_main);
        serviceState = findViewById(R.id.service_state);
        serviceDetail = findViewById(R.id.service_detail);
        networkName = findViewById(R.id.network_name);
        networkPort = findViewById(R.id.network_port);
        deviceList = findViewById(R.id.device_list);
        inputPort = findViewById(R.id.input_port);
        inputMaxBytes = findViewById(R.id.input_max_bytes);
        rootState = findViewById(R.id.root_state);
        xposedState = findViewById(R.id.xposed_state);
        logText = findViewById(R.id.log_text);
        toggleService = findViewById(R.id.toggle_service);

        Settings current = Settings.load(this);
        inputPort.setText(String.valueOf(current.port));
        inputMaxBytes.setText(String.valueOf(current.maxBytes));

        toggleService.setOnClickListener(v -> {
            if (StatusStore.read(this).alive()) {
                Settings.setEnabled(this, false);
                stopService(new android.content.Intent(this, SyncService.class));
            } else {
                Settings.setEnabled(this, true);
                ContextCompat.startForegroundService(this,
                        new android.content.Intent(this, SyncService.class));
            }
            handler.postDelayed(refresh, 200);
        });

        findViewById(R.id.apply_settings).setOnClickListener(v -> applySettings());
        findViewById(R.id.clear_log).setOnClickListener(v -> {
            LogStore.clear();
            logText.setText(R.string.log_empty);
        });
        handler.post(refresh);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    private void applySettings() {
        try {
            int port = Integer.parseInt(inputPort.getText().toString().trim());
            int maxBytes = Integer.parseInt(inputMaxBytes.getText().toString().trim());
            if (!Settings.validPort(port) || !Settings.validMaxBytes(maxBytes)) {
                throw new NumberFormatException();
            }
            Settings.save(this, port, maxBytes);
            if (StatusStore.read(this).alive()) {
                startForegroundService(new android.content.Intent(this, SyncService.class)
                        .setAction(SyncService.ACTION_RELOAD));
            }
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void render(StatusStore.Snapshot snapshot) {
        boolean alive = snapshot.alive();
        toggleService.setText(alive ? R.string.action_stop : R.string.action_start);
        if (!alive) {
            serviceState.setText(R.string.state_stopped);
            serviceDetail.setText(R.string.status_not_running);
        } else if (StatusStore.NO_NETWORK.equals(snapshot.state)) {
            serviceState.setText(R.string.state_no_network);
            serviceDetail.setText(snapshot.detail);
        } else {
            serviceState.setText(R.string.state_running);
            serviceDetail.setText(snapshot.detail == null ? "" : snapshot.detail);
        }
        networkName.setText(snapshot.network == null
                ? getString(R.string.status_unknown) : snapshot.network);
        Settings current = Settings.load(this);
        networkPort.setText(getString(R.string.label_port) + ": " + current.port + "   "
                + getString(R.string.label_max_bytes) + ": " + current.maxBytes);
        rootState.setText(getString(R.string.label_root) + ": "
                + getString(snapshot.rootAllowed ? R.string.status_ok : R.string.status_unavailable));
        xposedState.setText(getString(R.string.label_xposed) + ": "
                + getString(snapshot.xposedHooked ? R.string.status_ok : R.string.status_unavailable));

        deviceList.removeAllViews();
        if (snapshot.peers.isEmpty()) {
            deviceList.addView(text(alive ? R.string.devices_discovering : R.string.devices_none));
        } else {
            for (StatusStore.Peer peer : snapshot.peers) {
                MaterialCardView card = new MaterialCardView(this);
                card.setRadius(24f);
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setPadding(32, 24, 32, 24);
                TextView title = new TextView(this);
                title.setText(peer.name == null || peer.name.isEmpty() ? peer.id : peer.name);
                title.setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
                TextView detail = new TextView(this);
                detail.setText(peer.address + (peer.outgoing ? "  →" : "  ←"));
                detail.setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                body.addView(title);
                body.addView(detail);
                card.addView(body);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = 16;
                card.setLayoutParams(params);
                deviceList.addView(card);
            }
        }
    }

    private TextView text(int stringId) {
        TextView view = new TextView(this);
        view.setText(stringId);
        view.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        return view;
    }
}
