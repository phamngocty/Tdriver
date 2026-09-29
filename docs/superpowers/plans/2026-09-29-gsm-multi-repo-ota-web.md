# Kế Hoạch Triển Khai: Tính Năng Phát Hành OTA Đa Repo & Đồng Bộ Web Tĩnh Trên GSM

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mở rộng công cụ GSM (Git Smart Manager) để hỗ trợ phát hành OTA (APK/BIN) và đồng bộ trang web tĩnh (`index.html`) sang repo GitHub độc lập (ví dụ `phamngocty/tymapwebside`) qua GitHub REST API.

**Architecture:** Sử dụng GitHub Contents API để đẩy file HTML lên root của repo web, GitHub Releases API để tạo Tag và upload file APK với tên tuỳ chỉnh (`TYMAP.apk`), đồng thời lưu cấu hình repo đích vào metadata của project trong GSM.

**Tech Stack:** Python 3, Flask, Requests, Vue.js 3 (CDN), GitHub REST API v3.

---

### Task 1: Bổ sung hàm GitHub Contents API vào `gsm/gsm/api_utils.py`

**Files:**
- Modify: `gsm/gsm/api_utils.py`
- Test: `gsm/tests/test_api_utils_web.py`

- [ ] **Step 1: Viết test kiểm tra hàm `upload_or_update_github_file`**
- [ ] **Step 2: Thực thi test và xác nhận RED (test fail)**
- [ ] **Step 3: Triển khai hàm `upload_or_update_github_file(token, owner, repo, path_in_repo, local_file_path, commit_message)` trong `gsm/gsm/api_utils.py`**
- [ ] **Step 4: Chạy lại test và xác nhận GREEN (test pass)**

---

### Task 2: Cập nhật Backend API trong `gsm/app.py` & `gsm/gsm/ota_utils.py`

**Files:**
- Modify: `gsm/gsm/ota_utils.py`
- Modify: `gsm/app.py`
- Test: `gsm/tests/test_gsm_features.py`

- [ ] **Step 1: Bổ sung quét file web tĩnh trong `find_release_assets()` (`gsm/gsm/ota_utils.py`)**
- [ ] **Step 2: Cập nhật endpoint `/api/browse-file` trong `gsm/app.py` hỗ trợ duyệt file HTML**
- [ ] **Step 3: Cập nhật route `POST /api/projects/<project_id>/ota-release` trong `gsm/app.py` để xử lý `target_repo`, `apk_asset_name`, `sync_web`, `web_path`**
- [ ] **Step 4: Lưu cấu hình `ota_target_repo`, `ota_apk_asset_name`, `ota_web_path` vào project để ghi nhớ**

---

### Task 3: Cập nhật Giao Diện Frontend trong `gsm/templates/index.html` & `gsm/static/js/app.js`

**Files:**
- Modify: `gsm/templates/index.html`
- Modify: `gsm/static/js/app.js`

- [ ] **Step 1: Khai báo các biến trạng thái mới trong `gsm/static/js/app.js` (`otaTargetRepo`, `otaApkAssetName`, `otaSyncWeb`, `otaWebPath`)**
- [ ] **Step 2: Cập nhật `autoDetectOtaAssets()` và `submitOtaRelease()` gửi payload mới**
- [ ] **Step 3: Thêm khối điều khiển Kho Phát Hành GitHub & Đồng bộ Web vào `gsm/templates/index.html`**

---

### Task 4: Kiểm Thử Toàn Diện & Nghiệm Thu

**Files:**
- Test: `gsm/tests/`

- [ ] **Step 1: Chạy toàn bộ test suite của GSM (`pytest gsm/tests`)**
- [ ] **Step 2: Kiểm tra khả năng phát hiện assets trên dự án hiện tại (`Tdriver`)**
- [ ] **Step 3: Báo cáo kết quả hoàn tất cho người dùng**
