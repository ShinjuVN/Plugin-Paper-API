# PAB - PlayerAntiBan (Paper / Purpur / Leaf 1.21.11)

## Build
Cần JDK 21 + Gradle 8.x:

    gradle build

File jar: `build/libs/PAB-1.0.0.jar` -> bỏ vào thư mục `plugins/`.

## Lệnh (quyền `pab.admin`, mặc định OP)
- `/pab reload`
- `/pab add <player> <tin nhắn từ chối ban>` (hỗ trợ player offline; hỗ trợ màu `&` và `&#rrggbb`)
- `/pab remove <player>` (hỗ trợ offline)
- `/pab list`

Placeholder trong tin nhắn: `{player}`, `{executor}`, `{command}`.

## Cơ chế chặn (nhiều lớp)
1. **Tầng sự kiện**: `PlayerCommandPreprocessEvent` + `ServerCommandEvent` (người chơi, console, RCON...) - nhận diện
   lệnh ban theo tên/alias/fuzzy chứa "ban", cả dạng `/plugin ban X`, `/execute ... run ban X`, selector `@a`,
   UUID, IP, tên viết tắt.
2. **Tầng CommandMap**: bọc (wrap) mọi lệnh ban trong `knownCommands` -> chặn cả `Bukkit.dispatchCommand()` của plugin khác.
3. **Tầng kick**: huỷ `PlayerKickEvent` do ban (`Player#ban`, plugin tự kick "banned").
4. **Tầng đăng nhập**: ghi đè từ chối `KICK_BANNED` ở `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent`.
5. **Tầng quét**: task lặp (mặc định 2 tick) tự gỡ ban Profile + IP của người được bảo vệ khỏi ban-list của server.

Người thực thi lệnh sẽ nhận tin nhắn custom đã đặt khi `/pab add`.

## Giới hạn cần biết
- Ban do **proxy** (BungeeCord/Velocity) hoặc plugin ban chạy ở proxy thì PAB trên backend không can thiệp được.
- Plugin tự thao tác thẳng lên database/file riêng của nó: PAB chặn ở lệnh + đăng nhập, nhưng bản ghi ban
  trong DB của plugin đó vẫn tồn tại (người chơi vẫn vào được nhờ ghi đè đăng nhập).
- Không hỗ trợ Folia.
- `protect-ip: true` nghĩa là IP của người được bảo vệ cũng không thể bị ban-ip (kể cả người dùng chung IP).
