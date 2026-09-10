# Nâng cấp GSM thành Git Web GUI Đa năng - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng cấp GSM thành một trình quản lý Git Web GUI độc lập, đa năng trên localhost:8765, hỗ trợ duyệt/clone từ cả GitHub & Gitea, tải code cũ dạng ZIP tại mọi commit, rollback/checkout an toàn, cây thư mục phân cấp, đồ thị commit trực quan, so sánh diff xanh/đỏ, giải quyết xung đột 1-click, và tách biệt module OTA firmware thành tiện ích mở rộng.

**Architecture:** 
- Backend Python 3.10+ (Flask) mở rộng các endpoint API: `/api/github/repos`, `/api/git/archive-zip`, `/api/git/diff-detail`, `/api/git/resolve-conflict`, `/api/projects/toggle-ota`.
- Wrapper `git` CLI thực hiện các thao tác git an toàn, không phụ thuộc C-binding phức tạp.
- Frontend Single Page App (Vue 3) nhúng `@gitgraph/js`, tái cấu trúc giao diện Workspace với Cây thư mục mở/gập đa tầng, thanh công cụ Git đầy đủ phím tắt/mẫu commit, đồ thị commit phân nhánh với nút tải ZIP/checkout, và form Release chuẩn hóa.

**Tech Stack:** Python 3.10+, Flask, subprocess Git CLI, unittest, Vue.js 3, `@gitgraph/js`, CSS Flex/Grid.

---

### File Structure Map
- `d:/Documents\PlatformIO\Tdriver\gsm\gsm\api_utils.py`: Bổ sung `list_github_repos(token)`.
- `d:/Documents\PlatformIO\Tdriver\gsm\gsm\git_utils.py`: Bổ sung `git_archive_zip`, `git_diff_parsed`, `git_resolve_conflict`.
- `d:/Documents\PlatformIO\Tdriver\gsm\gsm\storage.py`: Thêm hỗ trợ lưu/đọc `enable_ota` trong metadata dự án.
- `d:/Documents\PlatformIO\Tdriver\gsm\app.py`: Đăng ký các route API mới (`/api/github/repos`, `/api/git/archive-zip`, `/api/git/diff-detail`, `/api/git/resolve-conflict`, `/api/projects/toggle-ota`).
- `d:/Documents\PlatformIO\Tdriver\gsm\tests\test_gsm_features.py`: Bộ kiểm thử tự động unittest cho các chức năng backend mới.
- `d:/Documents\PlatformIO\Tdriver\gsm\templates\base.html`: Nhúng thư viện `@gitgraph/js`.
- `d:/Documents\PlatformIO\Tdriver\gsm\templates\index.html`: Cập nhật tab Kho GitHub, File Tree phân cấp, Đồ thị commit mở rộng, Form Release chuẩn & OTA toggle, Visual Conflict Resolver.
- `d:/Documents\PlatformIO\Tdriver\gsm\static\js\app.js`: Thêm logic Vue cho GitHub repos, tải zip, diff viewer, conflict resolver, và toggle OTA.
- `d:/Documents\PlatformIO\Tdriver\gsm\static\css\style.css`: Thêm styling cho Diff xanh/đỏ, Conflict resolver 2 cột, Cây thư mục phân cấp, và thẻ tag GitHub.

---

### Task 1: Backend - GitHub Repos Listing API

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\gsm\api_utils.py`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\app.py`
- Test: `d:\Documents\PlatformIO\Tdriver\gsm\tests\test_gsm_features.py`

- [ ] **Step 1: Viết test unittest cho hàm `list_github_repos`**
Tạo file `tests/test_gsm_features.py` với test case kiểm tra mock call GitHub API trả về danh sách repo chuẩn format.

- [ ] **Step 2: Chạy test để xác nhận FAIL**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 3: Cài đặt hàm `list_github_repos` trong `gsm/api_utils.py`**
Gọi endpoint `https://api.github.com/user/repos?per_page=100&sort=updated` với token, trả về danh sách `{id, name, full_name, description, clone_url, private, default_branch, updated_at}`.

- [ ] **Step 4: Thêm endpoint `GET /api/github/repos` vào `app.py`**
Lấy GitHub token từ storage, gọi `list_github_repos(token)` và trả về JSON.

- [ ] **Step 5: Chạy test để xác nhận PASS**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 6: Commit**
`git add gsm/api_utils.py app.py tests/test_gsm_features.py`  
`git commit -m "feat(api): add GitHub repositories listing API"`

---

### Task 2: Backend - Git Archive ZIP & Tải Mã Nguồn Cũ

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\gsm\git_utils.py`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\app.py`
- Test: `d:\Documents\PlatformIO\Tdriver\gsm\tests\test_gsm_features.py`

- [ ] **Step 1: Viết test unittest cho `git_archive_zip`**
Thêm test case kiểm tra `git_archive_zip` đóng gói repository thành file `.zip` hợp lệ tại HEAD và kiểm tra đọc được danh sách file trong zip bằng `zipfile.ZipFile`.

- [ ] **Step 2: Chạy test để xác nhận FAIL**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 3: Cài đặt `git_archive_zip` trong `gsm/git_utils.py`**
Chạy `git archive --format=zip --output=<out_file> <target_ref>` trong cwd `project_path`.

- [ ] **Step 4: Thêm endpoint `GET /api/git/archive-zip` vào `app.py`**
Nhận params: `project_id`, `ref` (commit hash/branch/tag). Tạo file zip tạm và trả về qua `send_file(..., as_attachment=True, download_name=...)`.

- [ ] **Step 5: Chạy test để xác nhận PASS**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 6: Commit**
`git add gsm/git_utils.py app.py tests/test_gsm_features.py`  
`git commit -m "feat(git): add git archive zip export endpoint"`

---

### Task 3: Backend - Detailed Diff API & Conflict Resolution

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\gsm\git_utils.py`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\app.py`
- Test: `d:\Documents\PlatformIO\Tdriver\gsm\tests\test_gsm_features.py`

- [ ] **Step 1: Viết test unittest cho `git_diff_parsed` và `git_resolve_conflict`**
Thêm test case cho parser diff (phân loại dòng `add`, `del`, `ctx` và đánh số dòng), và test case cho conflict resolution (`--ours` / `--theirs`).

- [ ] **Step 2: Chạy test để xác nhận FAIL**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 3: Cài đặt `git_diff_parsed` và `git_resolve_conflict` trong `gsm/git_utils.py`**
  - `git_diff_parsed`: chạy `git diff -U3 <target>` hoặc `git show <commit>`, parse các hunk `@@ -old,len +new,len @@` thành từng dòng có `type`, `old_num`, `new_num`, `text`.
  - `git_resolve_conflict`: chạy `git checkout --ours/--theirs <path>` rồi `git add <path>`.

- [ ] **Step 4: Thêm endpoint `GET /api/git/diff-detail` và `POST /api/git/resolve-conflict` vào `app.py`**

- [ ] **Step 5: Chạy test để xác nhận PASS**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 6: Commit**
`git add gsm/git_utils.py app.py tests/test_gsm_features.py`  
`git commit -m "feat(git): add structured diff parser and conflict resolver"`

---

### Task 4: Backend - Tách biệt OTA & Cấu hình Dự án

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\gsm\storage.py`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\app.py`
- Test: `d:\Documents\PlatformIO\Tdriver\gsm\tests\test_gsm_features.py`

- [ ] **Step 1: Viết test unittest kiểm tra cờ `enable_ota`**
Xác nhận khi tạo mới hoặc cập nhật project, cờ `enable_ota` mặc định là `False`, và có thể toggle chuyển sang `True`.

- [ ] **Step 2: Chạy test để xác nhận FAIL**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 3: Cập nhật hàm lưu/sửa project trong `gsm/storage.py`**
Bảo toàn trường `enable_ota: bool` cho từng project trong `projects.json`.

- [ ] **Step 4: Thêm endpoint `POST /api/projects/<id>/toggle-ota` vào `app.py`**

- [ ] **Step 5: Chạy test để xác nhận PASS**
Chạy: `python -m unittest tests/test_gsm_features.py -v`

- [ ] **Step 6: Commit**
`git add gsm/storage.py app.py tests/test_gsm_features.py`  
`git commit -m "feat(projects): support enable_ota project toggle flag"`

---

### Task 5: Frontend - Tab "🐙 Kho GitHub" & 1-Click Clone

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\templates\index.html`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\js\app.js`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\css\style.css`

- [ ] **Step 1: Thêm nút Tab `🐙 Kho GitHub` vào Sidebar điều hướng**
Nút kích hoạt `navTab = 'github_browse'`, hiển thị danh sách repo GitHub dạng lưới card tương tự Gitea.

- [ ] **Step 2: Viết hàm `fetchDashboardGithubRepos()` trong `app.js`**
Gọi API `/api/github/repos`, quản lý trạng thái `githubLoading`, `githubRepos`, và ô tìm kiếm/lọc repository.

- [ ] **Step 3: Thêm nút "📥 Clone" 1-click cho mỗi card repo GitHub**
Khi bấm: gọi clone với thư mục đích người dùng đã cấu hình hoặc gợi ý, thêm ngay vào danh sách dự án local.

- [ ] **Step 4: Thêm CSS styling cho nhãn GitHub/Gitea và thẻ repo card**

- [ ] **Step 5: Commit**
`git add templates/index.html static/js/app.js static/css/style.css`  
`git commit -m "feat(ui): add GitHub repositories browse and clone tab"`

---

### Task 6: Frontend - Chuẩn hóa Panel Release & Tách Module OTA

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\templates\index.html`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\js\app.js`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\css\style.css`

- [ ] **Step 1: Tạo Form Release Chuẩn cho mọi dự án**
Bao gồm: `Tên Tag (v1.0.0)`, `Tiêu đề Release`, `Nội dung cập nhật (Changelog)`, `Chọn nền tảng đẩy lên (GitHub/Gitea/Cả hai)`, và nút `🚀 Tạo Release`.

- [ ] **Step 2: Đưa phần OTA (APK & ESP32 Firmware) vào khu vực tiện ích mở rộng**
Chỉ hiển thị khi `selectedProject.enable_ota === true`. Thêm nút công tắc bật/tắt OTA: `"⚙️ Bật tính năng nạp Firmware OTA cho dự án này"` ngay trong phần cài đặt của dự án.

- [ ] **Step 3: Thêm logic tạo Release tiêu chuẩn trong `app.js`**
Hỗ trợ tạo tag git và gọi API tạo release trên GitHub/Gitea mà không yêu cầu bắt buộc có file APK/bin.

- [ ] **Step 4: Commit**
`git add templates/index.html static/js/app.js static/css/style.css`  
`git commit -m "feat(ui): decouple standard release form from IoT OTA firmware module"`

---

### Task 7: Frontend - Đồ thị Commit Trực quan & Tải ZIP Code Cũ

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\templates\base.html`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\templates\index.html`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\js\app.js`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\css\style.css`

- [ ] **Step 1: Tích hợp `@gitgraph/js` trong `templates/base.html`**
Thêm script CDN `@gitgraph/js`.

- [ ] **Step 2: Nâng cấp bảng Commit Graph và panel chi tiết commit**
Hiển thị commit hash, author, date, message và danh sách tag/branch chips.

- [ ] **Step 3: Thêm nút "📥 Tải .ZIP" tại mỗi commit**
Khi bấm: gọi `window.location.href = '/api/git/archive-zip?id=' + selectedProject.id + '&ref=' + commit.hash`.

- [ ] **Step 4: Thêm nút "⏪ Khôi phục / Tạo nhánh" tại mỗi commit**
Mở hộp thoại cho phép chọn:
  1. Tạo nhánh mới từ commit này (an toàn, không sợ Detached HEAD).
  2. Checkout commit này.
  3. Revert commit này.

- [ ] **Step 5: Commit**
`git add templates/base.html templates/index.html static/js/app.js static/css/style.css`  
`git commit -m "feat(ui): add commit graph enhancements with zip download and safe rollback"`

---

### Task 8: Frontend - Cây thư mục Mở/Gập Đa tầng & Diff / Conflict Viewer

**Files:**
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\templates\index.html`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\js\app.js`
- Modify: `d:\Documents\PlatformIO\Tdriver\gsm\static\css\style.css`

- [ ] **Step 1: Tái cấu trúc Cây thư mục dạng Folder mở/gập đệ quy**
Hỗ trợ click vào thư mục để toggle đóng/mở thư mục con, có icon phù hợp với từng đuôi file, có ô tìm nhanh tên file (`Ctrl+P` hoặc search input).

- [ ] **Step 2: Xây dựng Trình xem Diff (Diff Viewer)**
Hiển thị bảng so sánh code với dòng xanh lá (`+`) cho code thêm vào, dòng đỏ (`-`) cho code xóa, đánh số dòng rõ ràng.

- [ ] **Step 3: Xây dựng Bộ giải quyết xung đột trực quan (Visual Conflict Resolver)**
Khi phát hiện file có trạng thái `Conflict`, hiển thị giao diện xem 2 cột (Code của bạn vs Code server) kèm 3 nút bấm: *"Giữ code của tôi"*, *"Lấy code từ server"*, *"Chấp nhận cả hai"*.

- [ ] **Step 4: Commit**
`git add templates/index.html static/js/app.js static/css/style.css`  
`git commit -m "feat(ui): add collapsible file tree, diff viewer, and visual conflict resolver"`

---

### Task 9: Kiểm thử Tích hợp & Nghiệm thu Tổng thể

**Files:**
- Test: Toàn bộ hệ thống trên `http://localhost:8765`

- [ ] **Step 1: Chạy toàn bộ test suite backend**
Chạy: `python -m unittest discover -s tests -p "test_*.py" -v`  
Xác nhận tất cả tests đều PASS.

- [ ] **Step 2: Khởi chạy GSM server**
Chạy `python app.py` trong background và kiểm tra các endpoint hoạt động mượt mà.

- [ ] **Step 3: Xác minh các luồng thao tác trên UI**
  - Mở tab "🐙 Kho GitHub" -> Kiểm tra hiển thị danh sách repo.
  - Chọn một dự án bất kỳ -> Kiểm tra Cây thư mục mở/gập và click xem file.
  - Mở Đồ thị Commit -> Bấm nút "📥 Tải .ZIP" một commit cũ -> Kiểm tra file zip tải về hoàn chỉnh.
  - Mở form "🏷️ Releases" -> Xác nhận giao diện sạch sẽ không có trường firmware/APK.
  - Bật công tắc OTA cho dự án IoT -> Xác nhận khu vực nạp OTA hiển thị lại bình thường.

- [ ] **Step 4: Commit và hoàn thiện tài liệu hướng dẫn**
`git add .`  
`git commit -m "chore: complete GSM general git GUI upgrade"`
