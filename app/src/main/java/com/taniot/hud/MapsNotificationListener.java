package com.taniot.hud;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
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

    // Pattern tìm khoảng cách (ví dụ: "250 m", "80m", "1,5 km", "1.2km", "500m")
    private final Pattern distancePattern = Pattern.compile("(\\d+(?:[.,]\\d+)?\\s*(?:m|km))", Pattern.CASE_INSENSITIVE);

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        String packageName = sbn.getPackageName();
        if (!GOOGLE_MAPS_PACKAGE.equals(packageName)) {
            return;
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;

        // 1. Thu thập TẤT CẢ các chuỗi văn bản có trong thông báo Google Maps
        StringBuilder textCollector = new StringBuilder();
        if (notification.tickerText != null) {
            textCollector.append(notification.tickerText).append(" ");
        }

        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof CharSequence) {
                textCollector.append(value.toString()).append(" ");
            } else if (value instanceof CharSequence[]) {
                for (CharSequence cs : (CharSequence[]) value) {
                    if (cs != null) textCollector.append(cs.toString()).append(" ");
                }
            }
        }

        String fullMessage = textCollector.toString().trim();
        Log.d(TAG, "[Google Maps Raw Text]: " + fullMessage);

        // 2. Trích xuất biểu tượng icon dẫn đường (Maneuver Icon) từ LargeIcon
        Bitmap maneuverBitmap = null;
        try {
            Icon largeIcon = notification.getLargeIcon();
            if (largeIcon != null) {
                Drawable drawable = largeIcon.loadDrawable(this);
                if (drawable != null) {
                    maneuverBitmap = drawableToBitmap(drawable);
                }
            }
            if (maneuverBitmap == null && extras.containsKey(Notification.EXTRA_LARGE_ICON)) {
                Object obj = extras.get(Notification.EXTRA_LARGE_ICON);
                if (obj instanceof Bitmap) {
                    maneuverBitmap = (Bitmap) obj;
                } else if (obj instanceof Icon) {
                    Drawable d = ((Icon) obj).loadDrawable(this);
                    if (d != null) maneuverBitmap = drawableToBitmap(d);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi đọc icon Google Maps: " + e.getMessage());
        }

        // 3. Phân tích hướng rẽ kết hợp cả Văn bản + Phân tích hình ảnh Icon
        String directionCode = parseDirection(fullMessage.toLowerCase(), maneuverBitmap);

        // 4. Phân tích khoảng cách rẽ
        String distance = parseDistance(fullMessage);

        // 5. Gửi lệnh chuẩn xuống ESP32
        if (directionCode != null && !distance.isEmpty()) {
            String command = directionCode + "," + distance;

            long now = System.currentTimeMillis();
            if (!command.equals(lastSentCommand) || (now - lastSentTime > 1500)) {
                lastSentCommand = command;
                lastSentTime = now;
                BleManager.getInstance().sendData(command);
                BleManager.getInstance().notifyLog("[Maps -> Xe] Hướng: " + directionCode + " | Cách: " + distance);
            }
        }
    }

    /**
     * Phân tích hướng rẽ thông minh:
     * Ưu tiên từ khóa tiếng Việt & tiếng Anh -> Nếu không có chữ rẽ thì phân tích pixel của Icon mũi tên!
     */
    private String parseDirection(String text, Bitmap iconBitmap) {
        // --- BƯỚC 1: KIỂM TRA ĐÍCH ĐẾN ---
        if (text.contains("đến nơi") || text.contains("đến đích") || text.contains("bạn đã đến")
                || text.contains("điểm đến") || text.contains("tới đích") || text.contains("arrived")
                || text.contains("destination")) {
            return "F";
        }

        // --- BƯỚC 2: KIỂM TRA QUAY ĐẦU (U-TURN) ---
        if (text.contains("quay đầu") || text.contains("vòng lại") || text.contains("vòng ngược lại")
                || text.contains("u-turn") || text.contains("uturn") || text.contains("make a u-turn")) {
            return "U";
        }

        // --- BƯỚC 3: KIỂM TRA RẼ TRÁI (Tất cả các biến thể từ ngữ) ---
        boolean hasLeft = text.contains("trái") || text.contains("quẹo trái") || text.contains("bên trái")
                || text.contains("sang trái") || text.contains("chếch trái") || text.contains("chếch sang trái")
                || text.contains("ngoặt trái") || text.contains("làn trái") || text.contains("tay trái")
                || text.contains("turn left") || text.contains("keep left") || text.contains("bear left")
                || text.contains("fork left") || text.contains("sharp left");

        // --- BƯỚC 4: KIỂM TRA RẼ PHẢI (Tất cả các biến thể từ ngữ) ---
        boolean hasRight = text.contains("phải") || text.contains("quẹo phải") || text.contains("bên phải")
                || text.contains("sang phải") || text.contains("chếch phải") || text.contains("chếch sang phải")
                || text.contains("ngoặt phải") || text.contains("làn phải") || text.contains("tay phải")
                || text.contains("turn right") || text.contains("keep right") || text.contains("bear right")
                || text.contains("fork right") || text.contains("sharp right");

        if (hasLeft && !hasRight) {
            return "L";
        }
        if (hasRight && !hasLeft) {
            return "R";
        }

        // --- BƯỚC 5: NẾU TEXT CHỈ CÓ TÊN ĐƯỜNG -> PHÂN TÍCH HÌNH ẢNH MŨI TÊN (LARGE ICON) ---
        if (iconBitmap != null) {
            String dirFromIcon = detectDirectionFromBitmap(iconBitmap);
            if (dirFromIcon != null) {
                return dirFromIcon;
            }
        }

        // --- BƯỚC 6: CHỈ TRẢ VỀ ĐI THẲNG NẾU RÕ RÀNG CÓ TỪ KHÓA ĐI THẲNG HOẶC ĐANG TRÊN ĐƯỜNG TỚI ĐÍCH ---
        if (text.contains("đi tiếp") || text.contains("đi thẳng") || text.contains("tiếp tục")
                || text.contains("chạy thẳng") || text.contains("continue") || text.contains("straight")
                || text.contains("head")) {
            return "S";
        }

        // Nếu Google Maps chỉ hiện tên đường tiếp theo mà không nói gì, giữ nguyên hướng rẽ trước đó nếu có
        if (lastSentCommand.startsWith("L,")) return "L";
        if (lastSentCommand.startsWith("R,")) return "R";
        if (lastSentCommand.startsWith("U,")) return "U";

        return "S";
    }

    /**
     * Thuật toán phân tích hình học pixel của Icon mũi tên chỉ đường từ Google Maps:
     * Đo độ phân bố pixel giữa nửa bên trái và nửa bên phải của icon để xác định hướng rẽ.
     */
    private String detectDirectionFromBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 12 || height < 12) return null;

        long leftPixels = 0;
        long rightPixels = 0;
        int midX = width / 2;

        // Vùng khảo sát: Bỏ qua 10% lề ngoài để tránh viền nhiễu
        int startX = width / 10;
        int endX = width - startX;
        int startY = height / 10;
        int endY = height - startY;

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int pixel = bitmap.getPixel(x, y);
                int alpha = (pixel >> 24) & 0xff;

                // Nếu pixel có màu (không phải pixel trong suốt)
                if (alpha > 40) {
                    if (x < midX - (width / 16)) {
                        leftPixels++;
                    } else if (x > midX + (width / 16)) {
                        rightPixels++;
                    }
                }
            }
        }

        long total = leftPixels + rightPixels;
        if (total < 30) return null; // Quá ít pixel không đủ kết luận

        double leftRatio = (double) leftPixels / (double) total;
        double rightRatio = (double) rightPixels / (double) total;

        Log.d(TAG, String.format("Phân tích Icon: Left=%.2f | Right=%.2f", leftRatio, rightRatio));

        // Mũi tên lệch rõ rệt sang trái (> 58%) -> RẼ TRÁI
        if (leftRatio > 0.58) {
            return "L";
        }
        // Mũi tên lệch rõ rệt sang phải (> 58%) -> RẼ PHẢI
        if (rightRatio > 0.58) {
            return "R";
        }

        // Nếu pixel cân đối 2 bên (sai lệch < 12%) -> MŨI TÊN ĐI THẲNG
        if (Math.abs(leftRatio - rightRatio) < 0.12) {
            return "S";
        }

        return (leftRatio > rightRatio) ? "L" : "R";
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }
        int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 64;
        int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 64;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private String parseDistance(String text) {
        Matcher matcher = distancePattern.matcher(text);
        if (matcher.find()) {
            String d = matcher.group(1);
            if (d != null) {
                return d.replaceAll("\\s+", "").replace(',', '.');
            }
        }
        return "100m";
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && GOOGLE_MAPS_PACKAGE.equals(sbn.getPackageName())) {
            lastSentCommand = "";
            BleManager.getInstance().sendData("F,0m");
            BleManager.getInstance().notifyLog("[Maps] Kết thúc chuyến đi");
        }
    }
}
