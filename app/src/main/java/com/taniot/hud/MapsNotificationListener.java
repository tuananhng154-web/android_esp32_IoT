package com.taniot.hud;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
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

        // 1. Thu thập thông tin văn bản từ Google Maps
        CharSequence titleChar = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subTextChar = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        String title = titleChar != null ? titleChar.toString() : "";
        String text = textChar != null ? textChar.toString() : "";
        String subText = subTextChar != null ? subTextChar.toString() : "";

        // Thu thập toàn bộ văn bản để không bỏ sót từ khóa nào
        StringBuilder textCollector = new StringBuilder();
        if (notification.tickerText != null) {
            textCollector.append(notification.tickerText).append(" ");
        }
        textCollector.append(title).append(" ").append(text).append(" ").append(subText).append(" ");

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

        // Hiển thị nội dung nhận được lên màn hình app
        BleManager.getInstance().notifyLog("[Maps Text]: " + (title.isEmpty() ? "" : title + " | ") + text);

        // 2. Tạo context của Google Maps để truy cập tài nguyên chính xác
        Context mapsContext = getMapsContext();

        // 3. Phân tích hướng rẽ theo cơ chế 4 tầng đa nhiệm
        String directionCode = parseDirection(fullMessage, notification, extras, mapsContext);

        // 4. Phân tích khoảng cách rẽ (ưu tiên Title trước)
        String distance = parseDistance(titleChar, textChar, fullMessage);

        // 5. Gửi lệnh chuẩn xuống ESP32
        if (directionCode != null && !distance.isEmpty()) {
            String command = directionCode + "," + distance;

            long now = System.currentTimeMillis();
            if (!command.equals(lastSentCommand) || (now - lastSentTime > 1200)) {
                lastSentCommand = command;
                lastSentTime = now;
                BleManager.getInstance().sendData(command);
                BleManager.getInstance().notifyLog("[HUD <<] " + getDirectionLabel(directionCode) + " " + distance);
            }
        }
    }

    private Context getMapsContext() {
        try {
            return createPackageContext(GOOGLE_MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            Log.w(TAG, "Không thể tạo mapsContext: " + e.getMessage());
            return this;
        }
    }

    private String getDirectionLabel(String code) {
        if ("L".equals(code)) return "RẼ TRÁI ⬅";
        if ("R".equals(code)) return "RẼ PHẢI ➡";
        if ("U".equals(code)) return "QUAY ĐẦU ↩";
        if ("F".equals(code)) return "TỚI ĐÍCH 🏁";
        return "ĐI THẲNG ⬆";
    }

    /**
     * HỆ THỐNG NHẬN DIỆN HƯỚNG RẼ 4 TẦNG ĐỘC LẬP:
     * - Tầng 1: Đọc tên tài nguyên hệ thống (Resource Entry Name) của Icon mũi tên trong Google Maps (Chính xác 100%)
     * - Tầng 2: Quét từ điển tiếng Việt tự nhiên phong phú trong văn bản thông báo
     * - Tầng 3: Phân tích hình học pixel (Centroid X) của nét vẽ mũi tên sau khi bóc tách nền xanh
     * - Tầng 4: Bộ nhớ trạng thái thông minh (giữ nguyên hướng khi đang tiếp cận điểm rẽ)
     */
    private String parseDirection(String fullText, Notification notification, Bundle extras, Context mapsContext) {
        String lower = fullText.toLowerCase();

        // ================= TẦNG 1: TỪ KHÓA ĐÍCH ĐẾN =================
        if (lower.contains("đến nơi") || lower.contains("đến đích") || lower.contains("bạn đã đến")
                || lower.contains("điểm đến") || lower.contains("tới đích") || lower.contains("tới nơi")
                || lower.contains("arrived") || lower.contains("destination")) {
            return "F";
        }

        // ================= TẦNG 2: TÊN TÀI NGUYÊN ICON CỦA GOOGLE MAPS (CHÍNH XÁC NHẤT) =================
        // Google Maps đặt tên các drawable mũi tên theo chuẩn: ic_turn_left, ic_turn_right, ic_uturn,...
        Icon smallIcon = notification.getSmallIcon();
        String dirFromSmallRes = detectDirectionFromResource(smallIcon, mapsContext, "SmallIcon");
        if (dirFromSmallRes != null) {
            return dirFromSmallRes;
        }

        Icon largeIcon = notification.getLargeIcon();
        String dirFromLargeRes = detectDirectionFromResource(largeIcon, mapsContext, "LargeIcon");
        if (dirFromLargeRes != null) {
            return dirFromLargeRes;
        }

        // ================= TẦNG 3: BỘ TỪ ĐIỂN TIẾNG VIỆT ĐA DẠNG =================
        // 1. Quay đầu (U-Turn)
        if (lower.contains("quay đầu") || lower.contains("vòng lại") || lower.contains("vòng ngược lại")
                || lower.contains("u-turn") || lower.contains("uturn")) {
            BleManager.getInstance().notifyLog("[Text Match]: QUAY ĐẦU (U-Turn)");
            return "U";
        }

        // 2. Rẽ trái
        boolean textHasLeft = lower.contains("rẽ trái") || lower.contains("quẹo trái") || lower.contains("bên trái")
                || lower.contains("sang trái") || lower.contains("chếch trái") || lower.contains("chếch sang trái")
                || lower.contains("ngoặt trái") || lower.contains("làn trái") || lower.contains("tay trái")
                || lower.contains("ôm cua trái") || lower.contains("về bên trái")
                || lower.contains("turn left") || lower.contains("keep left") || lower.contains("bear left")
                || lower.contains("fork left") || lower.contains("sharp left");

        // 3. Rẽ phải
        boolean textHasRight = lower.contains("rẽ phải") || lower.contains("quẹo phải") || lower.contains("bên phải")
                || lower.contains("sang phải") || lower.contains("chếch phải") || lower.contains("chếch sang phải")
                || lower.contains("ngoặt phải") || lower.contains("làn phải") || lower.contains("tay phải")
                || lower.contains("ôm cua phải") || lower.contains("về bên phải")
                || lower.contains("turn right") || lower.contains("keep right") || lower.contains("bear right")
                || lower.contains("fork right") || lower.contains("sharp right");

        if (textHasLeft && !textHasRight) {
            BleManager.getInstance().notifyLog("[Text Match]: RẼ TRÁI");
            return "L";
        }
        if (textHasRight && !textHasLeft) {
            BleManager.getInstance().notifyLog("[Text Match]: RẼ PHẢI");
            return "R";
        }

        // ================= TẦNG 4: PHÂN TÍCH HÌNH HỌC PIXEL ICON =================
        Bitmap largeBitmap = extractIconBitmap(largeIcon, extras.get(Notification.EXTRA_LARGE_ICON), mapsContext);
        if (largeBitmap == null && extras.containsKey("android.largeIcon.big")) {
            Object big = extras.get("android.largeIcon.big");
            if (big instanceof Bitmap) largeBitmap = (Bitmap) big;
        }
        Bitmap smallBitmap = extractIconBitmap(smallIcon, null, mapsContext);

        String dirLargePixel = detectDirectionFromBitmap(largeBitmap, "LargeIcon");
        String dirSmallPixel = detectDirectionFromBitmap(smallBitmap, "SmallIcon");

        if ("L".equals(dirLargePixel) || "R".equals(dirLargePixel)) {
            return dirLargePixel;
        }
        if ("L".equals(dirSmallPixel) || "R".equals(dirSmallPixel)) {
            return dirSmallPixel;
        }

        // ================= TẦNG 5: ĐI THẲNG NẾU RÕ RÀNG CÓ TỪ KHÓA =================
        if (lower.contains("đi tiếp") || lower.contains("đi thẳng") || lower.contains("tiếp tục")
                || lower.contains("chạy thẳng") || lower.contains("continue") || lower.contains("straight")
                || lower.contains("head")) {
            return "S";
        }

        if ("S".equals(dirLargePixel) || "S".equals(dirSmallPixel)) {
            return "S";
        }

        // ================= TẦNG 6: GIỮ NGUYÊN HƯỚNG KHI ĐANG TIẾP CẬN ĐIỂM RẼ =================
        if (lastSentCommand.startsWith("L,")) return "L";
        if (lastSentCommand.startsWith("R,")) return "R";
        if (lastSentCommand.startsWith("U,")) return "U";

        return "S";
    }

    /**
     * Nhận diện hướng rẽ trực tiếp từ Tên tài nguyên (Resource Name) của Icon trong Google Maps.
     * Google Maps đặt tên các drawable chỉ đường cực kỳ chuẩn chỉ bằng tiếng Anh:
     * Ví dụ: ic_turn_left, ic_turn_right, ic_sharp_left, ic_uturn,...
     */
    private String detectDirectionFromResource(Icon icon, Context mapsContext, String sourceTag) {
        if (icon == null || mapsContext == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (icon.getType() == Icon.TYPE_RESOURCE) {
                int resId = icon.getResId();
                if (resId != 0) {
                    try {
                        String resName = mapsContext.getResources().getResourceEntryName(resId).toLowerCase();
                        Log.d(TAG, "[" + sourceTag + " ResName]: " + resName);
                        BleManager.getInstance().notifyLog("[" + sourceTag + " Res]: " + resName);

                        if (resName.contains("uturn") || resName.contains("u_turn")) {
                            BleManager.getInstance().notifyLog("[Khớp Res]: QUAY ĐẦU (U-Turn)");
                            return "U";
                        }
                        if (resName.contains("left") || resName.contains("slight_left") || resName.contains("sharp_left")) {
                            BleManager.getInstance().notifyLog("[Khớp Res]: RẼ TRÁI ⬅");
                            return "L";
                        }
                        if (resName.contains("right") || resName.contains("slight_right") || resName.contains("sharp_right")) {
                            BleManager.getInstance().notifyLog("[Khớp Res]: RẼ PHẢI ➡");
                            return "R";
                        }
                        if (resName.contains("straight") || resName.contains("continue")) {
                            BleManager.getInstance().notifyLog("[Khớp Res]: ĐI THẲNG ⬆");
                            return "S";
                        }
                        if (resName.contains("destination") || resName.contains("finish") || resName.contains("flag")) {
                            BleManager.getInstance().notifyLog("[Khớp Res]: TỚI ĐÍCH 🏁");
                            return "F";
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Không tìm thấy tên tài nguyên cho icon: " + e.getMessage());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Thuật toán phân tích hình học pixel sau khi bóc tách nền xanh:
     */
    private String detectDirectionFromBitmap(Bitmap bitmap, String sourceName) {
        if (bitmap == null) return null;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 12 || height < 12) return null;

        int insetX = Math.max(2, width / 8);
        int insetY = Math.max(2, height / 8);

        int c1 = bitmap.getPixel(insetX, insetY);
        int c2 = bitmap.getPixel(width - insetX - 1, insetY);
        int c3 = bitmap.getPixel(insetX, height - insetY - 1);
        int c4 = bitmap.getPixel(width - insetX - 1, height - insetY - 1);

        int bgA = (((c1 >>> 24) & 0xff) + ((c2 >>> 24) & 0xff) + ((c3 >>> 24) & 0xff) + ((c4 >>> 24) & 0xff)) / 4;
        int bgR = (((c1 >>> 16) & 0xff) + ((c2 >>> 16) & 0xff) + ((c3 >>> 16) & 0xff) + ((c4 >>> 16) & 0xff)) / 4;
        int bgG = (((c1 >>> 8) & 0xff) + ((c2 >>> 8) & 0xff) + ((c3 >>> 8) & 0xff) + ((c4 >>> 8) & 0xff)) / 4;
        int bgB = ((c1 & 0xff) + (c2 & 0xff) + (c3 & 0xff) + (c4 & 0xff)) / 4;

        long leftPixels = 0;
        long rightPixels = 0;
        long totalArrowPixels = 0;
        long sumX = 0;
        int midX = width / 2;

        int startX = Math.max(1, width / 20);
        int endX = width - startX;
        int startY = Math.max(1, height / 20);
        int endY = height - startY;

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int pixel = bitmap.getPixel(x, y);
                int a = (pixel >>> 24) & 0xff;
                int r = (pixel >>> 16) & 0xff;
                int g = (pixel >>> 8) & 0xff;
                int b = pixel & 0xff;

                if (a < 40) continue;

                boolean isArrow = false;
                if (bgA < 50) {
                    isArrow = (a > 80);
                } else {
                    boolean isWhite = (r > 180 && g > 180 && b > 180);
                    int colorDiff = Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(b - bgB);
                    isArrow = isWhite || (colorDiff > 80);
                }

                if (isArrow) {
                    totalArrowPixels++;
                    sumX += x;

                    if (x < midX - (width / 24)) {
                        leftPixels++;
                    } else if (x > midX + (width / 24)) {
                        rightPixels++;
                    }
                }
            }
        }

        if (totalArrowPixels < 20) {
            return null;
        }

        double avgX = (double) sumX / (double) totalArrowPixels;
        double normAvgX = avgX / (double) width;

        double lrTotal = leftPixels + rightPixels;
        double leftRatio = lrTotal > 0 ? (double) leftPixels / lrTotal : 0.5;
        double rightRatio = lrTotal > 0 ? (double) rightPixels / lrTotal : 0.5;

        String debugMsg = String.format("[%s %dx%d] Pixels=%d | L=%.0f%% | R=%.0f%% | CenterX=%.2f",
                sourceName, width, height, totalArrowPixels, leftRatio * 100, rightRatio * 100, normAvgX);
        Log.d(TAG, debugMsg);
        BleManager.getInstance().notifyLog(debugMsg);

        if (leftRatio > 0.55 || normAvgX < 0.46) {
            return "L";
        }
        if (rightRatio > 0.55 || normAvgX > 0.54) {
            return "R";
        }

        return "S";
    }

    private Bitmap extractIconBitmap(Icon icon, Object fallback, Context mapsContext) {
        if (icon != null) {
            try {
                Context targetContext = (mapsContext != null) ? mapsContext : this;
                Drawable drawable = icon.loadDrawable(targetContext);
                if (drawable != null) {
                    return drawableToBitmap(drawable);
                }
            } catch (Exception e) {
                Log.w(TAG, "Lỗi loadDrawable với mapsContext: " + e.getMessage());
                try {
                    Drawable drawable = icon.loadDrawable(this);
                    if (drawable != null) return drawableToBitmap(drawable);
                } catch (Exception ignored) {}
            }
        }
        if (fallback instanceof Bitmap) {
            return (Bitmap) fallback;
        } else if (fallback instanceof Icon) {
            try {
                Context targetContext = (mapsContext != null) ? mapsContext : this;
                Drawable drawable = ((Icon) fallback).loadDrawable(targetContext);
                if (drawable != null) return drawableToBitmap(drawable);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }
        int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 72;
        int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 72;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private String parseDistance(CharSequence title, CharSequence text, String fullMessage) {
        if (title != null) {
            Matcher m = distancePattern.matcher(title);
            if (m.find()) return cleanDistance(m.group(1));
        }
        if (text != null) {
            Matcher m = distancePattern.matcher(text);
            if (m.find()) return cleanDistance(m.group(1));
        }
        Matcher m = distancePattern.matcher(fullMessage);
        if (m.find()) return cleanDistance(m.group(1));
        return "100m";
    }

    private String cleanDistance(String d) {
        if (d == null) return "100m";
        return d.replaceAll("\\s+", "").replace(',', '.');
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
