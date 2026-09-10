# Thiết kế Kỹ thuật: Nâng cấp GSM (Git Smart Manager) thành Git Web GUI Đa năng

**Ngày tạo:** 2026-09-11  
**Trạng thái:** Đã phê duyệt (Approved)  
**Mục tiêu:** Nâng cấp công cụ GSM (`localhost:8765`) thành một trình quản lý Git Web GUI độc lập, đa năng cho mọi dự án, hỗ trợ đầy đủ thao tác Git qua nút bấm (không cần nhớ lệnh), hiển thị cây thư mục chuẩn GitHub, đồ thị phân nhánh `@gitgraph/js`, duyệt/tải code cũ theo commit bất kỳ, và tách biệt tính năng OTA thành module mở rộng.

---

## 1. Bối cảnh & Mục tiêu (Context & Goals)

### 1.1 Vấn đề hiện tại
- Người dùng quản lý nhiều dự án trên cả GitHub và Gitea, việc gõ và nhớ các lệnh Git CLI (`pull`, `push`, `clone`, `checkout`, `merge`, `stash`, `rebase`, `reset`) phức tạp và dễ gây nhầm lẫn.
- Phiên bản GSM trước đây bị gắn chặt (hardcode) với dự án Tdriver (IoT/Firmware), trong đó bảng Release bắt buộc phải có thông tin OTA, file APK và file `.bin` ESP32, khiến các dự án phần mềm thông thường (web, python, backend...) không sử dụng được thuận tiện.
- Thiếu các tính năng trực quan hóa thiết yếu: cây thư mục đa tầng (hierarchical tree), đồ thị phân nhánh commit trực quan, tải mã nguồn tại commit cũ dạng ZIP, duyệt kho GitHub từ xa.

### 1.2 Mục tiêu đạt được
- **Đa năng & Độc lập**: Quản lý bất kỳ thư mục dự án Git nào trên máy tính.
- **Tách biệt Module OTA**: Form tạo Release chuẩn GitHub/Gitea cho mọi dự án; Module nạp firmware OTA chỉ kích hoạt khi dự án được bật cờ `enable_ota`.
- **Duyệt & 1-Click Clone từ GitHub & Gitea**: Tab "Kho GitHub" và "Kho Gitea" hiển thị toàn bộ repository cá nhân/tổ chức từ token đã lưu.
- **Trực quan hóa cao cấp**:
  - Cây thư mục (File Tree) dạng mở/gập phân cấp giống GitHub/VS Code, có icon và ô tìm kiếm nhanh.
  - Đồ thị Commit `@gitgraph/js` vẽ các nhánh phân tách màu sắc, thể hiện rõ luồng branch & merge.
  - Trình xem Diff (Diff Viewer) tô màu xanh/đỏ các dòng code thay đổi.
  - Bộ giải quyết xung đột (Visual Conflict Resolver) 1-click chọn Ours/Theirs.
- **Quản lý Phiên bản & Rollback an toàn**:
  - Cho phép tải file `.zip` chứa toàn bộ code tại bất kỳ commit/tag nào mà không làm xáo trộn working tree.
  - Hỗ trợ Checkout / Tạo nhánh mới / Rollback nhanh tại chỗ.
  - Đồng bộ 1-click lên cả GitHub và Gitea (Dual Push).

---

## 2. Kiến trúc Hệ thống (System Architecture)

### 2.1 Backend (Python 3.10+ / Flask)
Backend chạy dịch vụ web nhẹ tại `localhost:8765`, sử dụng wrapper `git` CLI:
- **Module Quản lý Kho lưu trữ (`gsm/api_utils.py`)**:
  - Bổ sung hàm `list_github_repos(token)` gọi endpoint GitHub REST API `/user/repos`.
- **Module Thao tác Git nâng cao (`gsm/git_utils.py`)**:
  - `git_archive_zip(project_path, commit_hash, output_zip_path)`: Đóng gói code tại commit thành file ZIP.
  - `git_diff_parsed(project_path, file_path, commit_hash)`: Trả về dữ liệu diff chi tiết dạng dòng `{type: 'add'|'del'|'ctx', old_no, new_no, text}`.
  - `git_resolve_conflict(project_path, file_path, choice)`: Giải quyết conflict theo chiến lược `--ours` hoặc `--theirs` và tự động `git add`.
  - `git_dual_push(project_path, branch, force)`: Đẩy commit lên cả remote `origin` (GitHub) và `gitea`.
- **Module Lưu trữ & Dự án (`gsm/storage.py`)**:
  - Bổ sung thuộc tính `enable_ota: bool` (mặc định `False`) cho mỗi dự án trong `projects.json`.
- **REST API Endpoints (`app.py`)**:
  - `GET /api/github/repos`: Lấy danh sách repo GitHub của người dùng.
  - `GET /api/git/archive-zip`: Tải file zip của commit hoặc release tag.
  - `GET /api/git/diff-detail`: Lấy diff có cấu trúc dòng để hiển thị bảng xanh/đỏ.
  - `POST /api/git/resolve-conflict`: Giải quyết xung đột file.
  - `POST /api/git/dual-push`: Đẩy code lên cả 2 remote.
  - `POST /api/projects/toggle-ota`: Bật/tắt tính năng OTA cho từng dự án.

### 2.2 Frontend (Vue.js 3 Single Page Application)
Giao diện chạy trực tiếp trên trình duyệt, không cần node build step:
- **Thư viện tích hợp**:
  - `@gitgraph/js` (hoặc bundle nhúng cục bộ/CDN an toàn): Dựng biểu đồ commit graph đẹp mắt dạng canvas/svg.
  - Component Cây thư mục (Folder Tree) đệ quy có mở/gập thư mục, icon loại file, tìm kiếm nhanh.
  - Diff view rendering với số dòng kép (Old Line / New Line) và nền màu xanh/đỏ.
- **Bố cục chính**:
  - **Sidebar**: Điều hướng giữa Trang chủ (Projects), Kho GitHub, Kho Gitea, Clone, Tạo mới, Cài đặt.
  - **Top Action Bar**: Pull, Push, Dual Push, Commit (kèm mẫu `feat:`, `fix:`, `docs:`, `update:`), Quản lý nhánh, Stash, Releases, Rollback.
  - **Workspace Split Panel**:
    - Trái: Cây thư mục dự án (hỗ trợ chọn xem nhánh/tag/commit bất kỳ).
    - Phải: Đồ thị commit phân nhánh + Bảng chi tiết commit (Diff Viewer, Tải ZIP, Checkout).
  - **Panel Release Chuẩn**:
    - Form tạo Release chuẩn: Tag, Title, Changelog, File attachments bất kỳ.
    - Khu vực OTA Assets: Chỉ hiển thị khi dự án có `enable_ota === true`.

---

## 3. Luồng dữ liệu chi tiết (Data Flow & Workflows)

### 3.1 Luồng Tải Code Cũ dạng ZIP
1. Người dùng mở Đồ thị Commit hoặc Danh sách Release.
2. Bấm nút **"📥 Tải ZIP"** tại commit `abc1234`.
3. Trình duyệt gửi yêu cầu `GET /api/git/archive-zip?id=<project_id>&hash=abc1234`.
4. Backend gọi `git archive --format=zip -o <temp_path> abc1234`.
5. Flask trả về file với header `Content-Disposition: attachment; filename="<project>-abc1234.zip"`.
6. File được tải trực tiếp xuống thư mục Downloads của người dùng mà không chạm vào thư mục code đang làm việc.

### 3.2 Luồng Rollback / Checkout an toàn
1. Người dùng chọn 1 commit cũ trên đồ thị.
2. Bấm nút **"⏪ Khôi phục"**:
   - Tùy chọn A (Khuyên dùng): **"Tạo nhánh mới từ commit này"** -> Tạo nhánh thử nghiệm an toàn, không sợ Detached HEAD.
   - Tùy chọn B: **"Checkout commit"** (chuyển tạm thời về commit cũ để xem).
   - Tùy chọn C: **"Hard Reset về commit này"** -> Hiện cảnh báo màu đỏ, yêu cầu xác nhận trước khi xóa các commit sau đó.

### 3.3 Luồng Duyệt và Clone từ Kho GitHub
1. Người dùng bấm tab **"🐙 Kho GitHub"** trên sidebar.
2. Frontend gọi `GET /api/github/repos`.
3. Backend lấy GitHub PAT từ cấu hình, gọi GitHub API và trả về danh sách các repo.
4. Giao diện hiển thị danh sách dạng Card (Tên, Mô tả, Khóa riêng tư/Công khai, Nhánh mặc định).
5. Người dùng bấm **"📥 Clone"** -> Mở hộp thoại chọn thư mục lưu trên máy -> Backend tự động clone và thêm vào danh sách dự án local.

### 3.4 Luồng Xử lý Xung đột Xanh/Đỏ (Visual Conflict Resolver)
1. Khi `git pull` gặp conflict, trạng thái dự án đánh dấu `has_conflict = True`.
2. Action bar hiển thị nút cảnh báo **"⚠️ Có xung đột mã nguồn"**.
3. Bấm vào danh sách file conflict, giao diện hiển thị 2 cột:
   - Cột trái: Code của bạn (Ours).
   - Cột phải: Code từ remote (Theirs).
4. Các nút bấm 1-click:
   - "Giữ code của tôi" -> chạy `git checkout --ours <file>` + `git add <file>`.
   - "Lấy code từ server" -> chạy `git checkout --theirs <file>` + `git add <file>`.
5. Sau khi xử lý hết các file conflict, nút "Hoàn tất Merge" kích hoạt để lưu commit merge an toàn.

---

## 4. Xử lý Lỗi & An toàn (Error Handling & Safety)

- **Tránh mất mát dữ liệu**: Thao tác Reset Hard luôn yêu cầu xác nhận. Thao tác checkout commit cũ luôn ưu tiên gợi ý tạo nhánh mới.
- **Lỗi mạng & Xác thực**: Khi gọi GitHub/Gitea API không thành công (token hết hạn, mạng ngắt kết nối), hiển thị thông báo tiếng Việt kèm gợi ý hướng khắc phục cụ thể.
- **Tránh xung đột Push (Non-fast-forward)**: Hiển thị hướng dẫn kéo code (Pull) hoặc giải quyết rebase trước khi cho phép Push.

---

## 5. Kế hoạch Kiểm thử & Xác nhận (Testing & Verification)

### 5.1 Kiểm thử Tự động (Backend Python Tests)
- `tests/test_archive.py`: Xác nhận `git_archive_zip` tạo file `.zip` hợp lệ tại commit hiện tại và giải nén kiểm tra toàn vẹn.
- `tests/test_github_api.py`: Xác nhận `list_github_repos` hoạt động đúng schema trả về.
- `tests/test_diff_parser.py`: Xác nhận `git_diff_parsed` chia đúng các khối dòng add, delete, context.
- `tests/test_ota_decoupling.py`: Xác nhận cờ `enable_ota` lưu và tải đúng theo từng dự án.

### 5.2 Kiểm thử Thực tế trên Trình duyệt (Manual Verification)
- Khởi động `GSM.bat` trên localhost:8765.
- Kiểm tra hiển thị tab Kho GitHub và Kho Gitea.
- Kiểm tra duyệt cây thư mục mở/gập và click xem nội dung file.
- Kiểm tra đồ thị commit và bấm tải file ZIP tại commit cũ.
- Kiểm tra tạo Release cho dự án thường (giao diện sạch không có OTA) và dự án IoT (có bật OTA).
