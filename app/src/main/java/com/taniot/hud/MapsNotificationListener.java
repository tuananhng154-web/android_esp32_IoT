package com.taniot.hud;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapsNotificationListener extends NotificationListenerService {
    private static final String TAG = "TanIoT_MapsListener";
    private static final String GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps";

    private String lastSentCommand = "";
    private long lastSentTime = 0;

    // Pattern tìm khoảng cách (ví dụ: "250 m", "80m", "1,5 km", "1.2km")
    private final Pattern distancePattern = Pattern.compile("(\\d+(?:[.,]\\d+)?\\s*(?:m|km))", Pattern.CASE_INSENSITIVE);

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        String packageName = sbn.getPackageName();
        if (!GOOGLE_MAPS_PACKAGE.equals(packageName)) {
            return; // Chỉ xử lý thông báo của Google Maps
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleChar = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subTextChar = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        String title = titleChar != null ? titleChar.toString() : "";
        String text = textChar != null ? textChar.toString() : "";
        String subText = subTextChar != null ? subTextChar.toString() : "";

        String fullMessage = (title + " " + text + " " + subText).trim();
        if (fullMessage.isEmpty()) return;

        Log.d(TAG, "Bắt được thông báo Maps: " + fullMessage);

        // 1. Phân tích hướng rẽ
        String directionCode = parseDirection(fullMessage.toLowerCase());

        // 2. Phân tích khoảng cách
        String distance = parseDistance(fullMessage);

        // 3. Đóng gói chuỗi lệnh: HƯỚNG,KHOẢNG_CÁCH (ví dụ: L,250m)
        if (directionCode != null && !distance.isEmpty()) {
            String command = directionCode + "," + distance;

            // Chống spam: Không gửi lại liên tục cùng 1 lệnh trong vòng 1 giây
            long now = System.currentTimeMillis();
            if (!command.equals(lastSentCommand) || (now - lastSentTime > 2000)) {
                lastSentCommand = command;
                lastSentTime = now;
                BleManager.getInstance().sendData(command);
            }
        }
    }

    private String parseDirection(String text) {
        // Rẽ Trái
        if (text.contains("rẽ trái") || text.contains("chếch sang trái") || text.contains("ngoặt trái")
                || text.contains("turn left") || text.contains("keep left") || text.contains("fork left")) {
            return "L";
        }
        // Rẽ Phải
        if (text.contains("rẽ phải") || text.contains("chếch sang phải") || text.contains("ngoặt phải")
                || text.contains("turn right") || text.contains("keep right") || text.contains("fork right")) {
            return "R";
        }
        // Quay Đầu
        if (text.contains("quay đầu") || text.contains("vòng ngược lại") || text.contains("u-turn")) {
            return "U";
        }
        // Đến đích
        if (text.contains("đến nơi") || text.contains("đến đích") || text.contains("bạn đã đến")
                || text.contains("arrived") || text.contains("destination")) {
            return "F";
        }
        // Đi Thẳng
        if (text.contains("đi tiếp") || text.contains("đi thẳng") || text.contains("tiếp tục")
                || text.contains("continue") || text.contains("head")) {
            return "S";
        }

        // Mặc định nếu đang dẫn đường mà chưa rõ hướng thì là đi thẳng
        return "S";
    }

    private String parseDistance(String text) {
        Matcher matcher = distancePattern.matcher(text);
        if (matcher.find()) {
            String d = matcher.group(1);
            if (d != null) {
                return d.replaceAll("\\s+", "").replace(',', '.');
            }
        }
        return "100m"; // Khoảng cách giả lập nếu không tìm thấy
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Khi người dùng tắt dẫn đường trên Google Maps
        if (sbn != null && GOOGLE_MAPS_PACKAGE.equals(sbn.getPackageName())) {
            lastSentCommand = "";
            BleManager.getInstance().sendData("F,0m");
        }
    }
}
