# Thiết Kế Kỹ Thuật: Tính Năng Phát Hành OTA Đa Repo & Đồng Bộ Web Tĩnh Trên GSM

- **Ngày thiết kế:** 2026-09-29
- **Công cụ:** GSM (Git Smart Manager)
- **Mục tiêu:** Cho phép GSM phát hành bản cập nhật OTA (APK/BIN) và đồng bộ trang web tĩnh (`index.html`) sang một GitHub Repository phân phối độc lập (ví dụ `phamngocty/tymapwebside`) tách biệt hoàn toàn với mã nguồn gốc của dự án.

---

## 1. Vấn Đề Kỹ Thuật Cần Giải Quyết

1. **Tách biệt 2 Repo:**
   - **Repo gốc (Source Code):** Chứa toàn bộ mã nguồn private C++, Android, NAS services... được quản lý bằng Git truyền thống (`git push` lên Gitea NAS hoặc GitHub gốc).
   - **Repo thứ 2 (Phát hành & Web):** Ví dụ `phamngocty/tymapwebside`, dùng cho **GitHub Pages** (yêu cầu file `index.html` nằm tại thư mục root) và **GitHub Releases** (lưu trữ file `TYMAP.apk` cho người dùng tải).
2. **Không thể dùng Git CLI thông thường để push chéo:**
   - Do 2 repo khác cấu trúc và không chung lịch sử commit, nếu dùng `git push` sẽ bị lỗi *unrelated histories* hoặc làm lộ toàn bộ code gốc sang repo web.
3. **Giải pháp:** Sử dụng **GitHub REST API** trực tiếp từ GSM để cập nhật file web và tải asset APK lên Release mà không đụng chạm đến Git history của repo gốc.

---

## 2. Kiến Trúc & Luồng Dữ Liệu

```text
[Dự án trên máy tính]
  │
  ├── 📂 Toàn bộ source code ──(Git Commit & Push)──► Gitea NAS / GitHub Source Repo
  │
  └── 📦 File phát hành (Release Assets):
        ├── File Web (web/index.html) ──(GitHub Contents API)──► Root index.html của tymapwebside
        ├── File APK (TYMAP.apk)       ──(GitHub Releases API)──► Release Tag v1.0.x của tymapwebside
        └── File Firmware BIN          ──(SFTP / Gitea NAS)   ──► Trạm NAS Fusion Engine
```

---

## 3. Các Thành Phần Thay Đổi Trong GSM

### A. Frontend (UI/UX) - `gsm/templates/index.html` & `gsm/static/js/app.js`
Trong Tab **"📡 Nạp Release OTA"**, bổ sung các trường điều khiển:
1. **Kho GitHub Phát Hành (Target Repo):**
   - Ô nhập text: `otaTargetRepo` (Ví dụ: `phamngocty/tymapwebside`).
   - Mặc định: Tự động lấy remote GitHub của project hoặc giá trị đã lưu.
2. **Tên File APK Khi Lên Release (Asset Name):**
   - Ô nhập text: `otaApkAssetName` (Mặc định: `TYMAP.apk`).
   - Giúp đổi tên từ `app-debug.apk` sang `TYMAP.apk` để giữ cố định link `releases/latest/download/TYMAP.apk`.
3. **Đồng Bộ Web Giới Thiệu (GitHub Pages):**
   - Checkbox: `[x] 🌐 Đồng bộ Web Giới Thiệu (GitHub Pages)` (`otaSyncWeb`).
   - Ô nhập: `Đường dẫn File Web HTML` (`otaWebPath`), có nút **📁 Duyệt HTML...** (Tự động nhận diện `web/index.html` hoặc `docs/index.html`).

### B. Backend API - `gsm/app.py` & `gsm/gsm/api_utils.py`
1. **API Upload Web HTML lên GitHub:**
   - Bổ sung hàm `upload_or_update_github_file(token, owner, repo, file_path_in_repo, local_file_path, commit_message)`:
     - Gọi `GET https://api.github.com/repos/{owner}/{repo}/contents/{path}` để lấy SHA hiện tại (nếu file đã tồn tại).
     - So sánh nội dung: nếu không thay đổi thì bỏ qua.
     - Gọi `PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}` với nội dung base64 và sha cũ để cập nhật đè.
2. **Cập nhật Route `/api/projects/<project_id>/ota-release`:**
   - Nhận thêm: `target_repo`, `apk_asset_name`, `sync_web`, `web_path`.
   - Nếu có `target_repo`: Sử dụng target repo này để tạo GitHub Release và upload file APK với tên `apk_asset_name`.
   - Nếu `sync_web == True` và `web_path` hợp lệ: Cập nhật file web lên target repo.
3. **Lưu Cấu Hình (Persistence):**
   - Lưu các giá trị `ota_target_repo`, `ota_apk_asset_name`, `ota_web_path` vào `project` trong `gsm/storage.py` để lần sau mở GSM tự động điền lại.

---

## 4. Tiêu Chí Nghiệm Thu (Success Criteria)

1. **Độc lập:** Bấm phát hành OTA trong GSM thì mã nguồn gốc vẫn được push lên Gitea/GitHub như cũ; file `web/index.html` được đồng bộ sang root của repo `phamngocty/tymapwebside`.
2. **Link cố định:** File APK được upload lên GitHub Release với tên chuẩn `TYMAP.apk`, link `https://github.com/phamngocty/tymapwebside/releases/latest/download/TYMAP.apk` luôn tải được bản mới nhất.
3. **Tổng quát:** Áp dụng được cho bất kỳ dự án nào khác trong GSM mà không bị gắn cứng (hardcode) tên repo.
