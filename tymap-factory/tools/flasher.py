#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TYMAP ESP32-C3 Dual-Boot Flash Utility
Ho tro nap tron goi ca 2 he dieu hanh (Android TYMAP BLE & iOS Sygic BLE)
"""

import sys
import os
import subprocess
import glob

# Ensure UTF-8 console output on Windows
if sys.platform.startswith("win"):
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

PYTHON_EXE = sys.executable
ESPTOOL_PY = os.path.expanduser(r"~\.platformio\packages\tool-esptoolpy\esptool.py")

BIN_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "binaries"))

# Partition Flash Offsets for ESP32-C3 (4MB)
OFFSETS = {
    "bootloader": "0x0",
    "partitions": "0x8000",
    "factory":    "0x10000",  # factory (896 KB)
    "android":    "0xF0000",  # ota_0 (768 KB)
    "ios":        "0x270000", # app_ios (768 KB)
}

FILES = {
    "bootloader": os.path.join(BIN_DIR, "bootloader.bin"),
    "partitions": os.path.join(BIN_DIR, "partitions.bin"),
    "factory":    os.path.join(BIN_DIR, "factory.bin"),
    "android":    os.path.join(BIN_DIR, "android.bin"),
    "ios":        os.path.join(BIN_DIR, "ios.bin"),
}

def get_port_list():
    ports_info = []
    try:
        import serial.tools.list_ports
        ports = list(serial.tools.list_ports.comports())
        for p in ports:
            desc = p.description.lower()
            is_rec = any(k in desc for k in ["esp", "usb-serial", "usb serial", "jtag", "ch340", "cp210", "uart", "cdc"])
            ports_info.append((p.device, p.description, is_rec))
    except Exception as e:
        pass
    return ports_info

def auto_detect_port():
    ports_info = get_port_list()
    for dev, desc, is_rec in ports_info:
        if is_rec:
            print(f"[*] Tim thay thiet bi ESP32 tren cong: {dev} ({desc})")
            return dev
    if ports_info:
        print(f"[*] Chon cong COM mac dinh dau tien: {ports_info[0][0]}")
        return ports_info[0][0]
    return "COM3"

def select_port_interactive():
    while True:
        ports = get_port_list()
        print("\n" + "="*60)
        print("          DANH SACH CONG COM KET NOI TREN MAY")
        print("="*60)

        default_port = None
        if not ports:
            print("  [!] Khong tim thay cong COM nao qua pyserial.")
            print("  [R] Thu quet lai cong COM (Refresh)")
            default_port = "COM3"
        else:
            for idx, (dev, desc, is_rec) in enumerate(ports, start=1):
                rec_tag = " <-- [Goi y ESP32]" if is_rec else ""
                if is_rec and not default_port:
                    default_port = dev
                print(f"  [{idx}] {dev} - {desc}{rec_tag}")
            if not default_port and ports:
                default_port = ports[0][0]
            print("  [R] Quet lai cong COM (Refresh)")

        print("-" * 60)
        prompt_txt = f"Chon cong COM [1-{len(ports)}], go ten cong (vd COM3), hoac [R] de quet lai [Mac dinh: {default_port}]: "
        try:
            choice = input(prompt_txt).strip()
        except (KeyboardInterrupt, EOFError):
            print("\nDa huy bo boi nguoi dung.")
            sys.exit(0)

        if not choice:
            if default_port:
                print(f"[*] Da chon cong: {default_port}")
                return default_port

        if choice.lower() == 'r':
            print("[*] Dang quet lai cong COM...")
            continue

        if choice.isdigit():
            idx = int(choice)
            if 1 <= idx <= len(ports):
                chosen = ports[idx - 1][0]
                print(f"[*] Da chon cong: {chosen}")
                return chosen
            else:
                print(f"[!] So thu tu khong hop le (chi tu 1 den {len(ports)}).")
                continue

        # Nguoi dung go truc tiep ten cong (vi du: COM4)
        if choice.upper().startswith("COM") or choice.startswith("/dev/"):
            chosen = choice.upper() if choice.upper().startswith("COM") else choice
            print(f"[*] Da chon cong: {chosen}")
            return chosen

        print("[!] Lua chon khong hop le, vui long thu lai.")

def run_esptool(cmd_args):
    if os.path.exists(ESPTOOL_PY):
        cmd = [PYTHON_EXE, ESPTOOL_PY] + cmd_args
    else:
        cmd = ["esptool.py"] + cmd_args
    print("\n" + "="*60)
    print("[RUN COMMAND]:", " ".join(cmd))
    print("="*60 + "\n")
    return subprocess.call(cmd)

def erase_flash(port, baud="921600"):
    print("\n" + "="*60)
    print(f"      TIEN HANH XOA TOAN BO FLASH (ERASE FLASH) [{port}]")
    print("="*60)
    args = [
        "--chip", "esp32c3",
        "--port", port,
        "--baud", baud,
        "--before", "default_reset",
        "--after", "hard_reset",
        "erase_flash",
    ]
    ret = run_esptool(args)
    if ret == 0:
        print("[*] Xoa toan bo Flash THANH CONG!")
    else:
        print("[!] Xoa Flash THAT BAI! Kiem tra lai ket noi hoac cong COM.")
    return ret

def flash_all(port, baud="921600"):
    print("\n========================================================")
    print("      TIEN HANH NAP TRON GOI TYMAP DUAL-BOOT")
    print("========================================================")
    for name, path in FILES.items():
        if not os.path.exists(path):
            print(f"[CANH BAO] File chua ton tai: {path}")

    args = [
        "--chip", "esp32c3",
        "--port", port,
        "--baud", baud,
        "--before", "default_reset",
        "--after", "hard_reset",
        "write_flash", "-z",
        "--flash_mode", "dio",
        "--flash_freq", "80m",
        "--flash_size", "4MB",
        OFFSETS["bootloader"], FILES["bootloader"],
        OFFSETS["partitions"], FILES["partitions"],
        OFFSETS["factory"],    FILES["factory"],
        OFFSETS["android"],    FILES["android"],
        OFFSETS["ios"],        FILES["ios"],
    ]
    return run_esptool(args)

def flash_single(target, port, baud="921600"):
    if target not in FILES:
        print(f"[LOI] Khong ho tro target: {target}")
        return 1
    path = FILES[target]
    offset = OFFSETS[target]
    if not os.path.exists(path):
        print(f"[LOI] Khong tim thay file: {path}")
        return 1

    print(f"\n[*] Dang nap [{target.upper()}] vao Offset {offset}...")
    args = [
        "--chip", "esp32c3",
        "--port", port,
        "--baud", baud,
        "--before", "default_reset",
        "--after", "hard_reset",
        "write_flash", "-z",
        "--flash_mode", "dio",
        offset, path,
    ]
    return run_esptool(args)

def interactive_menu():
    port = select_port_interactive()

    while True:
        print("\n" + "="*60)
        print("          MENU NAP FIRMWARE TYMAP DUAL-BOOT (ESP32-C3)")
        print(f"          Cong COM hien tai: [{port}]")
        print("="*60)
        print("  [1] Nap TRON GOI Dual-Boot (Bootloader + Partitions + Factory + Android + iOS)")
        print("  [2] Nap rieng Web Portal Factory (Offset 0x10000)")
        print("  [3] Nap rieng Android TYMAP BLE  (Offset 0xF0000 - ota_0)")
        print("  [4] Nap rieng iOS Sygic BLE      (Offset 0x270000 - app_ios)")
        print("  [5] Chi XOA TOAN BO FLASH (Erase Flash) - Khong nap firmware")
        print("  [C] Chon lai cong COM khac")
        print("  [0] Thoat")
        print("-" * 60)

        try:
            choice = input("Nhap lua chon [0-5 / C] [Mac dinh: 1]: ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nTam biet!")
            break

        if not choice:
            choice = "1"

        if choice == "0":
            print("\nTam biet!")
            break
        elif choice.lower() == "c":
            port = select_port_interactive()
            continue
        elif choice == "5":
            try:
                cf = input(f"Ban co CHAC CHAN muon xoa trang chip tren [{port}]? (y/N): ").strip().lower()
            except (KeyboardInterrupt, EOFError):
                break
            if cf in ["y", "yes"]:
                erase_flash(port)
            else:
                print("[*] Da huy lenh Erase Flash.")
            continue
        elif choice in ["1", "2", "3", "4"]:
            # Tùy chọn Erase Flash trước khi nạp
            try:
                erase_choice = input("\nBan co muon XOA FLASH (Erase Flash) truoc khi nap? (y/N) [Mac dinh: N]: ").strip().lower()
            except (KeyboardInterrupt, EOFError):
                break

            if erase_choice in ["y", "yes"]:
                ret = erase_flash(port)
                if ret != 0:
                    print("[!] Xoa flash that bai, huy bo qua trinh nap.")
                    continue

            # Tiến hành nạp theo lựa chọn
            if choice == "1":
                flash_all(port)
            elif choice == "2":
                flash_single("factory", port)
            elif choice == "3":
                flash_single("android", port)
            elif choice == "4":
                flash_single("ios", port)

            try:
                cont = input("\nBan co muon tiep tuc thao tac khac khong? (Y/n) [Mac dinh: Y]: ").strip().lower()
                if cont in ["n", "no"]:
                    print("\nHoan tat. Tam biet!")
                    break
            except (KeyboardInterrupt, EOFError):
                break
        else:
            print("[!] Lua chon khong hop le. Vui long chon tu 0 den 5 hoac C.")

def main():
    # Kiem tra neu chay khong co tham so -> vao Interactive Menu
    if len(sys.argv) == 1:
        interactive_menu()
        return

    # Xu ly cac lenh CLI thong thuong (ho tro backward compatibility)
    action = sys.argv[1].lower()

    # Kiem tra co tuy chon --erase khong
    do_erase = False
    args_filtered = []
    for a in sys.argv[1:]:
        if a.lower() in ["--erase", "-e", "erase"]:
            do_erase = True
        else:
            args_filtered.append(a)

    action = args_filtered[0].lower() if args_filtered else "all"

    port = auto_detect_port()
    if len(args_filtered) > 1:
        port = args_filtered[1]

    if do_erase or action == "erase":
        ret = erase_flash(port)
        if action == "erase":
            sys.exit(ret)
        if ret != 0:
            print("[!] Xoa flash that bai, dung tien trinh.")
            sys.exit(ret)

    if action == "all":
        sys.exit(flash_all(port))
    elif action in ["factory", "android", "ios", "bootloader", "partitions"]:
        sys.exit(flash_single(action, port))
    elif action in ["menu", "interactive"]:
        interactive_menu()
    else:
        print("Cach su dung:")
        print("  1. Chay Menu tuong tac:   python flasher.py")
        print("  2. Nap nhanh bang CLI:    python flasher.py [all|factory|android|ios|erase] [PORT] [--erase]")
        sys.exit(1)

if __name__ == "__main__":
    main()
