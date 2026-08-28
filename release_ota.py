#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TYMAP & Tdriver — Automated Release & OTA Distribution Tool
Tự động build APK, đóng gói Firmware, cập nhật version.json và phát hành lên Gitea NAS & GitHub.
"""

import os
import sys
import json
import subprocess
import urllib.request
import urllib.parse
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
TYMAP_DIR = ROOT_DIR / "TYMAP"
APK_PATH = TYMAP_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
VERSION_FILE = ROOT_DIR / "version.json"

GITEA_SERVER = "https://git.nas152.duckdns.org"
GITEA_LOCAL = "http://192.168.1.114:3002"
GITEA_OWNER = "nas152"
GITEA_REPO = "TYMAP"

def print_banner():
    print("=" * 65)
    print("      🚀 TYMAP & TDRIVER — CÔNG CỤ PHÁT HÀNH BẢN MỚI (RELEASE OTA)    ")
    print("=" * 65)

def run_cmd(cmd, cwd=None):
    print(f"⚙️  Thực thi: {cmd}")
    res = subprocess.run(cmd, shell=True, cwd=cwd or ROOT_DIR, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"❌ Lỗi ({res.returncode}): {res.stderr}")
        return False, res.stderr
    return True, res.stdout

def get_current_version():
    if VERSION_FILE.exists():
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "app": {"versionCode": 1, "versionName": "1.0.0", "apkUrl": "", "changelog": ""},
        "firmware": {"versionCode": 1, "versionName": "1.0.0", "binUrl": "", "changelog": ""}
    }

def build_android_apk():
    print("\n📦 [1/4] Đang Build Android APK (assembleDebug)...")
    gradle_cmd = ".\\gradlew.bat assembleDebug" if sys.platform == "win32" else "./gradlew assembleDebug"
    ok, out = run_cmd(gradle_cmd, cwd=TYMAP_DIR)
    if ok and APK_PATH.exists():
        size_mb = APK_PATH.stat().st_size / (1024 * 1024)
        print(f"✅ Build APK thành công! ({size_mb:.2f} MB)")
        print(f"📁 Đường dẫn: {APK_PATH}")
        return True
    else:
        print("❌ Build APK thất bại! Vui lòng kiểm tra lỗi code.")
        return False

def sync_version_to_nas(version_data):
    print("\n📡 [4/4] Đồng bộ trực tiếp version.json sang trạm NAS Fusion Engine...")
    try:
        import paramiko
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect("192.168.1.114", username="nas152", password="271000", timeout=5)
        
        # Ghi file tạm
        sftp = ssh.open_sftp()
        v_temp = ROOT_DIR / "scratch" / "version_temp.json"
        v_temp.parent.mkdir(parents=True, exist_ok=True)
        with open(v_temp, "w", encoding="utf-8") as f:
            json.dump(version_data, f, ensure_ascii=False, indent=2)
            
        sftp.put(str(v_temp), "/home/nas152/tymap_data/cameras/version.json")
        sftp.close()
        
        # Cập nhật quyền và reload Fusion Engine
        ssh.exec_command("docker restart tymap_fusion_engine")
        ssh.close()
        print("✅ Đã đồng bộ version.json sang máy chủ NAS (https://alert.nas152.duckdns.org/version.json)!")
    except Exception as e:
        print(f"⚠️ Không thể kết nối SSH NAS để đồng bộ tức thì: {e}")

def main():
    print_banner()
    curr = get_current_version()
    curr_app_ver = curr.get("app", {}).get("versionName", "1.0.0")
    curr_app_code = curr.get("app", {}).get("versionCode", 1)
    
    suggested_code = curr_app_code + 1
    parts = curr_app_ver.split(".")
    if len(parts) == 3 and parts[-1].isdigit():
        suggested_ver = f"{parts[0]}.{parts[1]}.{int(parts[-1]) + 1}"
    else:
        suggested_ver = f"1.0.{suggested_code}"

    print(f"📌 Phiên bản hiện tại: v{curr_app_ver} (Code: {curr_app_code})")
    print(f"💡 Phiên bản đề xuất : v{suggested_ver} (Code: {suggested_code})\n")

    tag_input = input(f"Nhập Tag phiên bản mới [{suggested_ver}]: ").strip()
    tag = tag_input if tag_input else suggested_ver
    if not tag.startswith("v"):
        tag_name = f"v{tag}"
        ver_name = tag
    else:
        tag_name = tag
        ver_name = tag.lstrip("v")

    code_input = input(f"Nhập Version Code mới [{suggested_code}]: ").strip()
    ver_code = int(code_input) if code_input.isdigit() else suggested_code

    print("\nNhập nội dung cập nhật (Changelog):")
    changelog_input = input("Changelog [Cập nhật cải tiến tính năng & sửa lỗi]: ").strip()
    changelog = changelog_input if changelog_input else "Cập nhật cải tiến tính năng & sửa lỗi."

    ask_build = input("\nBạn có muốn tự động build lại APK ngay bây giờ? (Y/n): ").strip().lower()
    if ask_build != "n":
        if not build_android_apk():
            return

    # Xác định URLs
    apk_download_url = f"{GITEA_SERVER}/{GITEA_OWNER}/{GITEA_REPO}/releases/download/{tag_name}/app-debug.apk"
    bin_download_url = f"{GITEA_SERVER}/{GITEA_OWNER}/{GITEA_REPO}/releases/download/{tag_name}/firmware.bin"

    new_version_data = {
        "app": {
            "versionCode": ver_code,
            "versionName": ver_name,
            "apkUrl": apk_download_url,
            "changelog": changelog
        },
        "firmware": {
            "versionCode": ver_code,
            "versionName": ver_name,
            "binUrl": bin_download_url,
            "changelog": changelog
        }
    }

    # 2. Ghi file version.json
    print("\n📝 [2/4] Ghi file version.json...")
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(new_version_data, f, ensure_ascii=False, indent=2)
    print("✅ Đã lưu version.json thành công.")

    # 3. Git Commit, Tag & Push
    print("\n🚀 [3/4] Commit Git & Tạo Tag...")
    run_cmd(f"git add version.json")
    run_cmd(f'git commit -m "release({tag_name}): publish update v{ver_name}"')
    run_cmd(f'git tag -a {tag_name} -m "Release {tag_name}: {changelog}"')
    
    ask_push = input("\nBạn có muốn push code & tag lên Git Remotes (Gitea/GitHub)? (Y/n): ").strip().lower()
    if ask_push != "n":
        run_cmd("git push")
        run_cmd(f"git push origin {tag_name}")

    # 4. Sync to NAS
    sync_version_to_nas(new_version_data)

    print("\n" + "=" * 65)
    print(f"🎉 PHÁT HÀNH HOÀN TẤT PHIÊN BẢN {tag_name}!")
    print(f"📱 App APK: {apk_download_url}")
    print(f"🌐 Link version: https://alert.nas152.duckdns.org/version.json")
    print("=" * 65 + "\n")

if __name__ == "__main__":
    main()
