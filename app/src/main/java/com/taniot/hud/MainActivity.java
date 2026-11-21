package com.taniot.hud;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements BleManager.BleStateListener {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private View viewStatusDot;
    private TextView tvBleStatus;
    private Button btnConnectBle;
    private TextView tvPermissionStatus;
    private Button btnGrantPermission;
    private TextView tvLiveLog;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkAndRequestPermissions();
        checkNotificationPermission();

        BleManager.getInstance().setListener(this);
    }

    private void initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvBleStatus = findViewById(R.id.tvBleStatus);
        btnConnectBle = findViewById(R.id.btnConnectBle);
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnGrantPermission = findViewById(R.id.btnGrantPermission);
        tvLiveLog = findViewById(R.id.tvLiveLog);
        TextView btnClearLog = findViewById(R.id.btnClearLog);

        tvLiveLog.setMovementMethod(new ScrollingMovementMethod());

        // Nút kết nối / ngắt kết nối BLE
        btnConnectBle.setOnClickListener(v -> {
            if (BleManager.getInstance().isConnected()) {
                BleManager.getInstance().disconnect();
            } else {
                BleManager.getInstance().startScanAndConnect(this);
            }
        });

        // Nút mở cài đặt cấp quyền đọc thông báo
        btnGrantPermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        // Các nút bấm test nhanh màn hình xe
        findViewById(R.id.btnTestLeft).setOnClickListener(v -> BleManager.getInstance().sendData("L,250m"));
        findViewById(R.id.btnTestRight).setOnClickListener(v -> BleManager.getInstance().sendData("R,80m"));
        findViewById(R.id.btnTestStraight).setOnClickListener(v -> BleManager.getInstance().sendData("S,1.2km"));
        findViewById(R.id.btnTestUturn).setOnClickListener(v -> BleManager.getInstance().sendData("U,150m"));
        findViewById(R.id.btnTestFinish).setOnClickListener(v -> BleManager.getInstance().sendData("F,0m"));

        // Nút xóa log
        btnClearLog.setOnClickListener(v -> {
            logBuilder.setLength(0);
            tvLiveLog.setText("[Đã xóa log]\n");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermission();
    }

    private void checkNotificationPermission() {
        boolean isGranted = isNotificationServiceEnabled();
        if (isGranted) {
            tvPermissionStatus.setText("✓ Đã cấp quyền đọc Google Maps thành công!");
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            btnGrantPermission.setVisibility(View.GONE);
        } else {
            tvPermissionStatus.setText("⚠ Cần cấp quyền để app tự động bắt mũi tên rẽ");
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_yellow));
            btnGrantPermission.setVisibility(View.VISIBLE);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Cần cấp đủ quyền Bluetooth để kết nối với xe!", Toast.LENGTH_SHORT).show();
                    break;
                }
            }
        }
    }

    @Override
    public void onConnectionStateChange(boolean connected, String message) {
        if (connected) {
            viewStatusDot.setBackgroundResource(R.drawable.shape_dot_connected);
            tvBleStatus.setText(R.string.status_connected);
            tvBleStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            btnConnectBle.setText(R.string.btn_disconnect);
            btnConnectBle.setBackgroundColor(ContextCompat.getColor(this, R.color.status_disconnected));
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.shape_dot_disconnected);
            tvBleStatus.setText(R.string.status_disconnected);
            tvBleStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
            btnConnectBle.setText(R.string.btn_connect);
            btnConnectBle.setBackgroundColor(ContextCompat.getColor(this, R.color.primary));
        }
    }

    @Override
    public void onDataSent(String data) {
        onLog(">> Đã gửi lệnh: " + data);
    }

    @Override
    public void onLog(String log) {
        String time = timeFormat.format(new Date());
        String entry = "[" + time + "] " + log + "\n";
        logBuilder.append(entry);

        tvLiveLog.setText(logBuilder.toString());
    }
}
