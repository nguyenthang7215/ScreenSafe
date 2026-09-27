# ScreenSafe

ScreenSafe là prototype Android dùng `WebView` để mở website và kiểm tra khả năng đọc:

- Text trong DOM.
- URL, metadata và pixel mẫu của ảnh.
- Metadata và frame mẫu của video.
- Tín hiệu audio cơ bản bằng Web Audio API.
- Nội dung mới được thêm vào DOM sau khi trang tải hoặc người dùng scroll.

Prototype không có AI, backend hoặc database. Ứng dụng không vượt qua CORS, same-origin policy, DRM hay cơ chế bảo vệ nội dung.

## 1. Yêu cầu

- Android Studio.
- Android SDK tương thích với project.
- Emulator hoặc điện thoại Android API 24 trở lên.
- Thiết bị có kết nối Internet.
- Android System WebView/Chrome được bật trên thiết bị.

## 2. Chạy bằng Android Studio

1. Mở Android Studio.
2. Chọn **Open** và mở thư mục project `ScreenSafe`.
3. Chờ Gradle Sync hoàn tất.
4. Chọn một emulator, ví dụ `Pixel_7`, hoặc kết nối điện thoại đã bật USB debugging.
5. Chọn cấu hình chạy module `app`.
6. Nhấn **Run**.

Nếu chưa có emulator:

1. Mở **Device Manager**.
2. Chọn **Create Virtual Device**.
3. Chọn một thiết bị Pixel.
4. Chọn system image Android API 24 trở lên.
5. Khởi động emulator rồi chạy lại ứng dụng.

## 3. Build bằng command line

Trên Windows PowerShell, chạy tại thư mục project:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK được tạo tại:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Nếu Gradle trên máy không ghi được vào cache mặc định:

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-local'
.\gradlew.bat testDebugUnitTest assembleDebug
```

## 4. Cài APK bằng ADB

Khi emulator hoặc điện thoại đã kết nối:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Nếu lệnh `adb` chưa có trong `PATH`, dùng đường dẫn `adb.exe` trong Android SDK, thường là:

```text
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

## 5. Cách sử dụng

### Bước 1 — Mở website

1. Nhập URL vào ô trên cùng, ví dụ:

   ```text
   https://www.wikipedia.org
   ```

2. Nhấn **Mở**.
3. Đợi website tải xong.

Nếu URL không có `http://` hoặc `https://`, ứng dụng tự thêm `https://`.

### Bước 2 — Đọc Text, Image và Video

Sau khi trang tải xong, ScreenSafe tự động quét DOM. Bạn cũng có thể nhấn:

```text
Quét lại và lưu JSON
```

Giao diện sẽ hiển thị:

```text
TEXT: số ký tự
IMAGE: tổng số ảnh và số ảnh đọc được pixel
VIDEO: tổng số video và số video đọc được frame
AUDIO: số thẻ audio riêng
LIMIT: số giới hạn phát hiện được
```

Ví dụ:

```text
TEXT: 4784
IMAGE: 29 | đọc pixel: 25/29
VIDEO: 2 | đọc frame: 2/2
AUDIO riêng: 0
LIMIT: 1
```

### Bước 3 — Kiểm tra nội dung tải thêm

1. Scroll website trong WebView.
2. Nếu website thêm DOM mới, `MutationObserver` tự động quét lại.
3. Report mới có nguồn `MUTATION` trong giao diện, JSON và Logcat.

### Bước 4 — Kiểm tra audio của video

1. Tìm video trong trang và nhấn Play.
2. Bảo đảm video không bị mute nếu muốn kiểm tra tín hiệu âm thanh.
3. Nhấn **Đo audio (phát video trước)**.
4. Đợi khoảng một giây.

Kết quả audio gồm:

- `rms`: mức năng lượng trung bình của tín hiệu.
- `peak`: biên độ lớn nhất quan sát được.
- `nonSilent`: có phát hiện tín hiệu khác im lặng hay không.
- `reason`: giải thích kết quả hoặc nguyên nhân không đọc được.

Nếu video đang pause, kết quả hợp lệ sẽ giống:

```json
{
  "paused": true,
  "rms": 0,
  "peak": 0,
  "nonSilent": false,
  "reason": "Media is paused; play it and probe again."
}
```

## 6. Xem file kết quả

ScreenSafe lưu report trên thiết bị tại:

```text
/storage/emulated/0/Android/data/com.example.screensafe/files/reports/latest_report.json
/storage/emulated/0/Android/data/com.example.screensafe/files/reports/latest_audio_probe.json
```

### Lấy file bằng ADB

```powershell
adb pull /sdcard/Android/data/com.example.screensafe/files/reports/latest_report.json .
adb pull /sdcard/Android/data/com.example.screensafe/files/reports/latest_audio_probe.json .
```

`latest_report.json` chứa:

- URL và tiêu đề trang.
- Nội dung Text, tối đa 50.000 ký tự.
- Danh sách ảnh và URL ảnh.
- Kích thước ảnh, trạng thái tải và pixel RGBA mẫu.
- Danh sách video và metadata.
- Trạng thái đọc frame và pixel RGBA frame mẫu.
- Thông tin/capability audio của video.
- Các giới hạn và fallback đề xuất.

`latest_audio_probe.json` chứa kết quả đo audio bằng Web Audio API.

Mỗi lần quét sẽ ghi đè file `latest_report.json`. Mỗi lần đo audio sẽ ghi đè `latest_audio_probe.json`.

## 7. Xem Logcat

Trong Android Studio:

1. Mở cửa sổ **Logcat**.
2. Chọn đúng emulator hoặc điện thoại.
3. Lọc bằng:

   ```text
   tag:ScreenSafe
   ```

Hoặc dùng ADB:

```powershell
adb logcat -s ScreenSafe
```

Các nhóm log chính:

```text
[INITIAL][TEXT]
[INITIAL][IMAGE]
[INITIAL][VIDEO]
[INITIAL][AUDIO]
[MUTATION][TEXT]
[MUTATION][IMAGE]
[MUTATION][VIDEO]
[AUDIO_PROBE]
[LIMIT]
[REPORT]
```

## 8. Website dùng để test

### Text

```text
https://example.com
```

Kỳ vọng: `TEXT > 0`.

### Text và Image

```text
https://www.wikipedia.org
```

Kỳ vọng: `TEXT > 0`, `IMAGE > 0` và có URL ảnh trong JSON.

### Text, Image, Video và frame

```text
https://www.w3schools.com/html/html5_video.asp
```

Kỳ vọng:

- Có Text và Image.
- Có thẻ Video.
- Có `duration`, `videoWidth` và `videoHeight`.
- `frameReadable=true` nếu canvas được phép đọc frame.
- Phát video rồi nhấn **Đo audio** để thử RMS/peak.

## 9. Cách hiểu kết quả

### Text

- `length > 0`: đọc được Text từ DOM.
- `content`: nội dung thực tế trả về Kotlin và lưu trong JSON.
- `truncated=true`: trang có hơn 50.000 ký tự và report đã giới hạn kích thước.

### Image

- `src`: URL ảnh.
- `complete=true`: ảnh đã tải xong.
- `pixelReadable=true`: canvas đọc được pixel, ảnh có thể đưa vào pipeline phân tích.
- `pixelSampleRgba`: một pixel mẫu chứng minh dữ liệu ảnh đã đọc được.
- `pixelReadable=false`: ảnh chưa tải hoặc bị cross-origin/CORS làm canvas tainted.

### Video

- `currentSrc`: nguồn video trình duyệt đang sử dụng.
- `duration`, `currentTime`: thời lượng và vị trí phát.
- `videoWidth`, `videoHeight`: kích thước frame.
- `frameReadable=true`: canvas đọc được frame video.
- `frameSampleRgba`: pixel mẫu của frame.
- `drmMediaKeysPresent=true`: có dấu hiệu protected/DRM media; ứng dụng không bypass.

### Audio

- `supported=true`: Web Audio API tồn tại.
- `contextState=running`: audio context đã chạy.
- `measured=true`: quá trình lấy sample đã thực hiện.
- `nonSilent=true`: đo được tín hiệu âm thanh khác im lặng.
- `nonSilent=false`: có thể do pause, mute, im lặng, CORS, DRM hoặc media chưa decode.

## 10. Giới hạn và fallback

- Cross-origin iframe: không đọc được DOM bên trong từ trang cha.
- CORS: có thể cho phép hiển thị media nhưng chặn đọc pixel/sample.
- Blob/MediaSource: URL tạm thời, không phải URL media có thể tái sử dụng.
- DRM/protected media: không trích xuất và không bypass.
- Canvas/WebGL: không có cấu trúc Text/Image DOM thông thường.
- Website virtualized: chỉ các phần tử đang tồn tại trong DOM được đọc.

Các fallback được report đề xuất nhưng chưa tự động triển khai:

- Ảnh/video: Android Screen Capture bằng MediaProjection với sự đồng ý của người dùng.
- Audio: Android Playback Capture với sự đồng ý của người dùng và khi nguồn phát cho phép.
- DRM hoặc protected surface: không có fallback bypass.

## 11. Xử lý lỗi thường gặp

### Trang không tải

- Kiểm tra Internet trên emulator.
- Kiểm tra URL có đúng không.
- Thử mở một trang HTTPS đơn giản như `https://example.com`.
- Kiểm tra Logcat để tìm `LOAD ERROR`.

### Kết quả bằng 0

- Đợi trang tải xong rồi nhấn **Quét lại và lưu JSON**.
- Scroll để kích hoạt lazy loading.
- Website có thể dùng iframe, canvas, WebGL hoặc Shadow DOM đặc biệt.

### Ảnh có URL nhưng không đọc được pixel

- Ảnh có thể chưa tải xong: đợi rồi quét lại.
- Ảnh có thể bị CORS/cross-origin làm canvas tainted.
- URL có thể cần cookie, header đăng nhập hoặc đã hết hạn.

### Video có metadata nhưng không đọc được frame

- Phát video rồi quét lại.
- Kiểm tra `readyState`, `videoWidth` và `videoHeight`.
- Video có thể dùng cross-origin, MediaSource hoặc DRM.

### Audio luôn bằng 0

- Phát video trước khi nhấn **Đo audio**.
- Kiểm tra video có mute hay không.
- Thử một video HTML5 thông thường.
- Cross-origin và DRM có thể làm Web Audio trả dữ liệu im lặng.

## 12. File liên quan

- Code chính: `app/src/main/java/com/example/screensafe/MainActivity.kt`
- Giao diện: `app/src/main/res/layout/activity_main.xml`
- Manifest: `app/src/main/AndroidManifest.xml`
- Báo cáo kỹ thuật: `SCREENSAFE_PROTOTYPE_REPORT.md`
- Ảnh kết quả: `screensafe-final.png`
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`

