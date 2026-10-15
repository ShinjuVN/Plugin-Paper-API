# MusicBox – bản tối ưu mạng & chống lỗi link

## Lỗi đã sửa (HTTP 400 / "error 200" / `header=`)
- **URL được chuẩn hóa** trước khi gửi: tên file tiếng Việt, dấu cách, `[ ] | { }`, tên miền Unicode… không còn gây HTTP 400.
- **Tự thử 6 kiểu kết nối** (header trình duyệt / tối giản / curl / URL dạng khác) khi host trả 400/403/404/405/406/415/429/5xx hoặc 200 rỗng; **nhớ kiểu đã chạy được theo từng host**.
- **Không tin Content-Type**: nhận diện mp3/ogg/wav bằng nội dung file. Link trả về trang HTML sẽ báo rõ thay vì lỗi khó hiểu.
- **Redirect**: xử lý `Location` có ký tự lạ/tương đối, phát hiện vòng lặp.
- **Thông báo lỗi dễ hiểu** cho người chơi; chi tiết kỹ thuật (`header=...`) chỉ ghi vào console.
- Mặc định `allowed-domains: []` (cho mọi tên miền: catbox.moe, github.io, archive.org…).
- Link `github.com/.../blob/...`, Dropbox `dl=0`, Google Drive `/file/d/ID` được đổi tự động sang link tải trực tiếp.

## Tiết kiệm dữ liệu mạng
| Thay đổi | Hiệu quả |
|---|---|
| **Ngắt tải khi dừng nhạc** (`/musicbox cancel`, thoát game, đổi bài) | Không tải nốt cả file (tối đa 30MB) vô ích |
| **Cache trên đĩa** (`plugins/MusicBox/cache`, LRU, mặc định 200MB) | Nghe lại cùng link = 0 byte mạng |
| **Hỏi lại host bằng ETag/Last-Modified** khi cache hết hạn | Host trả 304: chỉ tốn vài trăm byte |
| **Gộp tải trùng**: nhiều người nghe cùng bài | Chỉ tải 1 lần, người sau dùng bản cache |
| **Kiểm tra link khi `/musicbox add`** (Range đầu file, vài KB) | Link hỏng bị từ chối ngay, không lưu rồi mới lỗi |
| **Giới hạn tải đồng thời** (`max-concurrent-downloads`, mặc định 3) | Không bị tải dồn dập; bài đã bị dừng khi đang xếp hàng thì bỏ qua |
| Giảm số lần thử khi host báo 429/5xx | Không dồn thêm request vào host đang quá tải |

## Cấu hình mới (`config.yml`)
```yaml
settings:
  allowed-domains: []          # [] = mọi tên miền
  verify-on-add: true          # kiểm tra link khi /musicbox add
  max-concurrent-downloads: 3  # cần restart để đổi
cache:
  enabled: true
  max-size-mb: 200
  ttl-hours: 24                # 0 = luôn hỏi lại host (vẫn nhận 304)
```
Thông báo mới: `verifying`, `link-check-failed`, `link-check-skipped`, `cache-cleared`.

> **Lưu ý khi nâng cấp:** file `plugins/MusicBox/config.yml` trên server **không tự bị ghi đè**.
> Các khóa mới tự dùng giá trị mặc định trong jar, nhưng bạn phải tự sửa
> `allowed-domains` thành `[]` (hoặc xóa dòng `- "catbox.moe"`), rồi chạy `/musicbox reload`.

## Lệnh mới
- `/musicbox clearcache` (quyền `musicbox.admin`): xóa cache nhạc.

## File thay đổi
`AudioLoader.java` (viết lại), `AudioCache.java` (mới), `PlaybackManager.java`, `MusicCommand.java`,
`MusicBoxPlugin.java`, `config.yml`. Các file còn lại giữ nguyên.
