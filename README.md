# Bomb Guess Online (Java 17 · Swing · MySQL)

## Cấu trúc
- `src/main/java/com/bombgame/server` – GameServer, ClientHandler, GameRoom (trọng tài), PlayerDao, Database, DbPool, Config
- `src/main/java/com/bombgame/client` – ClientMain, NetClient, `ui/` (Login, Lobby, Game, hiệu ứng)
- `sql/schema.sql` – tạo database bombgame, bảng Players
- `config.properties` – cấu hình server (DB, cổng); có thể ghi đè bằng `-Ddb.pass=...`

## Cấu hình
Sửa `config.properties` (hoặc truyền `-Ddb.url=... -Ddb.user=... -Ddb.pass=... -Dgame.port=...`):
```
db.url=jdbc:mysql://localhost:3306/bombgame
db.user=root
db.pass=1234
game.port=9999
```
Server tự tạo bảng và tự nâng cấp schema cũ (thêm cột `password_salt`, đổi `score` sang `DECIMAL(10,1)`, thêm index).

## Chạy
1. MySQL: bật MySQL 8 chạy ở cổng 3306, user `root` / mật khẩu `1234` (chạy `sql/schema.sql` hoặc để server tự tạo DB/bảng).
2. `mvn compile`
3. Server: `mvn --% exec:java -Dexec.mainClass=com.bombgame.server.GameServer`
4. Client (mở nhiều cửa sổ): `mvn --% exec:java -Dexec.mainClass=com.bombgame.client.ClientMain`

> Không có Maven? Dùng JDK 17+ trực tiếp:
> ```
> javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src\main\java -Filter *.java).FullName
> java -cp "out;mysql-connector-j-8.3.0.jar" com.bombgame.server.GameServer
> java -cp "out;mysql-connector-j-8.3.0.jar" com.bombgame.client.ClientMain
> ```

## Chạy 2 máy cùng 1 mạng
1. 1 máy làm sevver và client , 1 máy client
2. máy server 
  mvn compile
  mvn --% exec:java -Dexec.mainClass=com.bombgame.server.GameServer
  mvn --% exec:java -Dexec.mainClass=com.bombgame.client.ClientMain
  
  ipconfig để tìm ip máy server 
3. máy client
  mkdir out
  javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src\main\java\com\bombgame\client -Filter *.java).FullName
  java -cp out com.bombgame.client.ClientMain
  
  localhost của client : ip máy server:9999


## Giao diện ván đấu (mới)
- **Bảng số 1–100**: bấm để chọn, bấm đúp để gửi ngay; hoặc gõ phím số (cả numpad), `Backspace` xoá 1 số, `Esc/Delete` xoá hết, `Enter` gửi.
- Số được chọn/gõ **bật lên** (pop + phát sáng) và **được đọc to** bằng tiếng Việt ("bốn mươi lăm").
- Khi có kết quả: số của bạn rồi số đối thủ **lần lượt bật ra**, có sóng xung kích, đọc to ("Bạn chọn …", "Đối thủ chọn …").
- Ô ngoài dải an toàn **vỡ dần theo sóng lan** từ chỗ hai người chọn; vòng đếm ngược 60 vạch, 5 giây cuối tick + đỏ + rung.
- Bom nổ: flash, 4 vòng sóng xung kích, tia lửa, rung màn hình, đọc "Quả bom là …"; thắng thì bắn pháo giấy.
- Âm thanh hiệu ứng tự tổng hợp (không cần file). Nút **ÂM THANH** bật/tắt cả tiếng đọc lẫn hiệu ứng.

### Đọc to hoạt động thế nào
Không cần thư viện ngoài, dùng giọng của hệ điều hành (`ui/Speaker.java`):
- Windows: System.Speech. Có giọng tiếng Việt (vi-VN) thì đọc tiếng Việt, không có thì đọc số bằng giọng mặc định (tiếng Anh).
- macOS: `say -v Linh`.  Linux: `espeak-ng` / `espeak` / `spd-say` (cần cài, ví dụ `sudo apt install espeak-ng`).

## Cải tiến giao diện & hiệu năng (bản làm mới UI)

**Giảm lag**
- Gộp toàn bộ timer rời rạc (mỗi nút / panel / nền / game từng có 1 Timer chạy mãi) thành **một đồng hồ duy nhất** `Anim`:
  mỗi chu kỳ chỉ cập nhật logic rồi repaint đúng 1 lần. Ngoài sảnh 30 FPS, trong ván 60 FPS, cửa sổ ẩn thì ngủ đông.
- Animation tính theo **thời gian thực (dt)** nên máy chậm không làm hiệu ứng "chạy chậm" theo.
- Quầng sáng, bóng mềm, chữ phát sáng (blur thật), avatar, thân quả bom… được vẽ **một lần vào ảnh cache** (`Fx`) rồi chỉ `drawImage`.
  100 ô số ở trạng thái đứng yên được vẽ bằng sprite dựng sẵn; chỉ ô đang hover / chọn / vỡ mới vẽ trực tiếp.
- `Theme.font()` có cache (trước đây tạo `Font` mới hàng trăm lần mỗi khung hình).
- Bộ điều tốc tự động hạ chất lượng hiệu ứng (`Anim.quality()` 2→1→0) nếu máy không theo kịp.
- Giọng đọc / âm thanh nạp sẵn ở luồng nền, ghi vào tiến trình PowerShell không còn chặn giao diện; giới hạn số clip âm thanh chồng nhau.

**Đẹp hơn**
- Nền: cực quang chuyển động, bokeh trôi, sao lấp lánh có tia sáng, sao băng, sàn lưới synthwave, quỹ đạo năng lượng, parallax theo chuột.
- Kính mờ có viền gradient + đốm sáng "comet" chạy quanh; nút neon có halo, vệt sáng quét, hiệu ứng nhấn.
- Đăng nhập: quả bom nhún với tia lửa + vệ tinh quay, tiêu đề gradient chảy, ô nhập bo tròn có hiệu ứng focus.
- Sảnh chờ: avatar theo tên, huy chương top 3, pill trạng thái, hàng hover/chọn bo tròn, thanh cuộn mảnh, chip người dùng.
- Ván đấu: vignette đỏ cảnh báo khi còn ≤5 giây, đốm sáng ở đầu vòng đếm giờ, pháo giấy lật 3D + pháo hoa khi thắng.
- Hộp thoại neon (`NeonDialog`) thay cho `JOptionPane`; sửa các chuỗi thiếu dấu tiếng Việt.
