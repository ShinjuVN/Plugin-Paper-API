# ISend

Plugin **gửi đồ giữa người chơi** qua giao diện hòm thư (GUI) cho Minecraft server **Paper / Purpur / Leaf 1.21.11**.
Hỗ trợ thu phí chuyển phát qua **Vault**, gửi cho cả người đang offline, chặn người gửi, và nhiều thứ khác.

> **English summary:** ISend is a Paper 1.21.x plugin that lets players mail items to each other (online or offline) through a chest GUI, with Vault-based delivery fees, per-player block/exception lists, a paginated inbox, and fully configurable Vietnamese/MiniMessage messages.

---

## Tính năng

- Gửi đồ bằng GUI 27 ô: 18 ô trên để đặt đồ, hàng dưới là thanh điều khiển.
- Nút gửi **đỏ** khi chưa có đồ, **xanh** khi đã có ít nhất 1 vật phẩm.
- Thu phí chuyển phát (phí cố định + phí theo số lượng item) qua Vault, tự hoàn tiền nếu lỗi lưu dữ liệu.
- Hòm thư 54 ô có phân trang, nhận từng món hoặc **Nhận tất cả**.
- Xem đồ theo từng người gửi (`/isend from`).
- Bật/tắt nhận đồ, chặn người chơi, danh sách ngoại lệ.
- Lưu dữ liệu từng người chơi (`userdata/<uuid>.yml`), không mất đồ khi restart.
- Thông báo khi có người gửi đồ và khi vào server mà còn thư chưa nhận.
- Toàn bộ GUI và tin nhắn chỉnh được trong `config.yml` (hỗ trợ MiniMessage và mã màu `&`).
- Admin có thể xem và lấy đồ trong hòm thư người khác.

## Phiên bản server hỗ trợ

| Server | Hỗ trợ | Ghi chú |
|---|---|---|
| **Paper 1.21.11** | Có | Phiên bản build chính |
| **Purpur 1.21.11** | Có | Fork của Paper, dùng chung API |
| **Leaf 1.21.11** | Có | Fork của Paper, dùng chung API |
| Paper 1.21 – 1.21.10 | Dự kiến chạy được | Chưa test |
| Pufferfish | Dự kiến chạy được | Chưa test |
| Spigot / CraftBukkit | **Không** | Plugin dùng Paper API (Adventure) |
| Folia | **Không** | Chưa hỗ trợ scheduler theo vùng; plugin tự tắt khi phát hiện Folia |
| Paper 1.20.x trở xuống | **Không** | `api-version` là 1.21 |
| Velocity / BungeeCord | Không áp dụng | Đây là plugin cho server game, không phải proxy |

**Yêu cầu:** Java 21, [Vault](https://www.spigotmc.org/resources/vault.34315/) và một plugin kinh tế (ví dụ EssentialsX). Không có Vault thì plugin vẫn chạy nhưng không thu được phí chuyển phát.

## Cài đặt

1. Tải `ISend.jar` ở mục **Releases**.
2. Bỏ vào thư mục `plugins/` rồi khởi động lại server.
3. Chỉnh `plugins/ISend/config.yml` theo ý muốn, sau đó dùng `/isend reload`.

## Lệnh

| Lệnh | Mô tả |
|---|---|
| `/isend` | Bật/tắt chế độ nhận đồ |
| `/isend to <người chơi>` | Mở GUI gửi đồ |
| `/isend gui` (hoặc `inbox`) | Mở hòm thư của bạn |
| `/isend from <người chơi>` | Chỉ xem đồ do người đó gửi |
| `/isend block <người chơi>` | Chặn / bỏ chặn người gửi |
| `/isend exception <người chơi>` | Thêm / xóa ngoại lệ (được gửi dù bạn tắt nhận đồ hoặc đã chặn) |
| `/isend reload` | Reload config *(admin)* |
| `/isend see <người chơi>` | Xem và lấy đồ trong hòm thư người khác *(admin)* |
| `/isend config` | Xem cấu hình hiện tại *(admin)* |

## Quyền (permissions)

| Quyền | Mặc định | Mô tả |
|---|---|---|
| `isend.use` | Mọi người | Dùng các lệnh thường |
| `isend.admin` | OP | `reload`, `see`, `config` |

## Cấu hình nhanh

```yaml
settings:
  delivery-fee: 500.0        # phí cố định mỗi lần gửi
  charge-per-item: 0.0       # phí thêm nhân với tổng số lượng item
  max-items-per-mail: 18     # số ô vật phẩm tối đa mỗi lần gửi
  allow-sending-to-self: false
  drop-on-full: true         # túi đầy: đồ thừa rơi xuống đất (false = giữ lại trong hòm thư)
```

Tên GUI, vật liệu, vị trí nút và mọi tin nhắn đều nằm trong `config.yml`.
Placeholder: `<player>`, `<sender>`, `<cost>`, `<count>`, `<max>`, `<items>`, `<stacks>`, `<page>`, `<pages>`, `<time>`.

## Tự build

Cần JDK 21.

```bash
git clone https://github.com/<tên-github-của-bạn>/ISend.git
cd ISend
./gradlew build        # Windows: gradlew.bat build
```

File jar nằm ở `build/libs/ISend.jar`.

## Góp ý và đóng góp

- Báo lỗi hoặc đề xuất tính năng: mở **Issues** (nhớ ghi phiên bản server, phiên bản plugin và log lỗi).
- Muốn sửa code: **Fork** repo, tạo nhánh mới, rồi gửi **Pull Request**.

## Giấy phép

Phát hành theo giấy phép [MIT](LICENSE).
