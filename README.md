# TanIoT HUD - Ứng dụng Android Dẫn Đường Xe Máy Thông Minh

Ứng dụng Android cầu nối: Tự động bắt thông báo dẫn đường thời gian thực từ **Google Maps** và truyền qua **Bluetooth Low Energy (BLE)** tới màn hình HUD **ESP32-C3** trên xe máy.

---

## 1. Cấu trúc dự án
Dự án được viết chuẩn Native Android (Java / AndroidX) tương thích từ Android 8.0 (API 26) đến Android 14 (API 34):

* **`BleManager.java`**: Quản lý quét và tự động kết nối với thiết bị Bluetooth `ESP32C3_HUD` qua Nordic UART Service.
* **`MapsNotificationListener.java`**: Lắng nghe thông báo của Google Maps, tự động bóc tách từ ngữ tiếng Việt/tiếng Anh (`rẽ trái` -> `L`, `rẽ phải` -> `R`, số mét `250m`...) và gửi lệnh xuống xe.
* **`MainActivity.java`**: Giao diện điều khiển, hiển thị trạng thái kết nối, nút cấp quyền và bộ phím bấm test nhanh.

---

## 2. Hướng dẫn mở và Build file APK trên Android Studio

1. **Mở dự án:**
   * Mở phần mềm **Android Studio**.
   * Chọn **Open** $\rightarrow$ Trỏ tới thư mục:
     ```text
     c:\Users\TUAN ANH PC\.gemini\antigravity-ide\scratch\esp32c3-hud-pio\android-app
     ```
   * Chờ Android Studio đồng bộ Gradle (khoảng 1 - 2 phút).

2. **Xuất file APK cài đặt:**
   * Trên thanh menu trên cùng, bấm: **Build** $\rightarrow$ **Build Bundle(s) / APK(s)** $\rightarrow$ **Build APK(s)**.
   * Khi build xong, ở góc dưới bên phải màn hình sẽ có thông báo: *APK(s) generated successfully* $\rightarrow$ bấm vào chữ **locate** để lấy file `app-debug.apk`.
   * Gửi file `.apk` này qua Zalo / Telegram hoặc cắm cáp chép vào điện thoại Android để cài đặt.

3. **Hoặc nạp trực tiếp qua cáp điện thoại:**
   * Bật **Chế độ gỡ lỗi USB (USB Debugging)** trên điện thoại Android.
   * Cắm cáp kết nối điện thoại với máy tính.
   * Bấm nút **Run (Tam giác xanh `▶`)** trên Android Studio để cài thẳng vào máy.

---

## 3. Cách sử dụng trên điện thoại

1. **Mở App TanIoT HUD:**
   * Bấm vào nút **"MỞ CÀI ĐẶT CẤP QUYỀN"** $\rightarrow$ Tìm tên `TanIoT HUD` và gạt bật cho phép truy cập thông báo.
2. **Kết nối với xe:**
   * Bấm nút **"KẾT NỐI VỚI XE"** $\rightarrow$ Chờ 2-3 giây, đèn trạng thái chuyển sang màu **XANH LÁ** (`ĐÃ KẾT NỐI XE`).
3. **Thử nghiệm:**
   * Bạn có thể bấm thử các nút trong mục **"3. BỘ BẤM TEST NHANH"** (`Trái 250m`, `Phải 80m`, `Thẳng 1.2km`...) để thấy màn hình xe đổi ngay lập tức.
   * Mở ứng dụng **Google Maps** thật trên máy, gõ điểm đến và bấm **Bắt đầu** $\rightarrow$ Màn hình xe sẽ tự động nhảy mũi tên theo lộ trình dẫn đường!
