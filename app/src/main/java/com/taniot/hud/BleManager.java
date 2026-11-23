package com.taniot.hud;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "TanIoT_BleManager";

    // Cấu hình UUID theo chuẩn Nordic UART Service trên ESP32
    public static final String TARGET_DEVICE_NAME = "ESP32C3_HUD";
    public static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");

    private static BleManager instance;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;

    private boolean isConnected = false;
    private boolean isScanning = false;
    private BleStateListener stateListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface BleStateListener {
        void onConnectionStateChange(boolean connected, String message);
        void onDataSent(String data);
        void onLog(String log);
    }

    private BleManager() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static synchronized BleManager getInstance() {
        if (instance == null) {
            instance = new BleManager();
        }
        return instance;
    }

    public void setListener(BleStateListener listener) {
        this.stateListener = listener;
    }

    public boolean isConnected() {
        return isConnected;
    }

    @SuppressLint("MissingPermission")
    public void startScanAndConnect(Context context) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyLog("[Lỗi] Bluetooth trên điện thoại chưa được bật!");
            return;
        }

        if (isConnected) {
            disconnect();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            notifyLog("[Lỗi] Không thể khởi động BLE Scanner");
            return;
        }

        isScanning = true;
        notifyLog("[Quét] Đang tìm thiết bị " + TARGET_DEVICE_NAME + "...");

        // Tự động dừng quét sau 10 giây nếu không thấy
        mainHandler.postDelayed(() -> {
            if (isScanning && !isConnected) {
                stopScan();
                notifyLog("[Hết giờ] Không tìm thấy " + TARGET_DEVICE_NAME + ". Hãy đảm bảo ESP32 đã bật nguồn.");
            }
        }, 10000);

        bluetoothLeScanner.startScan(new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if (TARGET_DEVICE_NAME.equals(name)) {
                    stopScan();
                    notifyLog("[Tìm thấy] Đang kết nối tới " + name + "...");
                    connectToDevice(context, device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                isScanning = false;
                notifyLog("[Lỗi quét] Mã lỗi: " + errorCode);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false;
            bluetoothLeScanner.stopScan(new ScanCallback() {});
        }
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(Context context, BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true;
                    notifyLog("[Thành công] Đã kết nối với xe! Đang tìm dịch vụ...");
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false;
                    rxCharacteristic = null;
                    notifyConnectionState(false, "Mất kết nối với xe!");
                    notifyLog("[Ngắt kết nối] Đã ngắt kết nối với ESP32");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BluetoothGattService service = gatt.getService(SERVICE_UUID);
                    if (service != null) {
                        rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID);
                        if (rxCharacteristic != null) {
                            notifyConnectionState(true, "Đã sẵn sàng truyền dữ liệu!");
                            notifyLog("[Sẵn sàng] Kênh truyền Nordic UART đã thông suốt!");
                        }
                    }
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    public boolean sendData(String data) {
        if (!isConnected || bluetoothGatt == null || rxCharacteristic == null) {
            notifyLog("[Chưa gửi] Chưa kết nối Bluetooth với xe!");
            return false;
        }

        try {
            rxCharacteristic.setValue(data.getBytes(StandardCharsets.UTF_8));
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean success = bluetoothGatt.writeCharacteristic(rxCharacteristic);
            if (success) {
                if (stateListener != null) {
                    mainHandler.post(() -> stateListener.onDataSent(data));
                }
                notifyLog("[>>> Gửi xuống xe]: " + data);
            }
            return success;
        } catch (Exception e) {
            notifyLog("[Lỗi gửi]: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
        rxCharacteristic = null;
        notifyConnectionState(false, "Đã ngắt kết nối");
    }

    private void notifyConnectionState(boolean connected, String message) {
        if (stateListener != null) {
            mainHandler.post(() -> stateListener.onConnectionStateChange(connected, message));
        }
    }

    public void notifyLog(String log) {
        if (stateListener != null) {
            mainHandler.post(() -> stateListener.onLog(log));
        }
        Log.d(TAG, log);
    }
}
