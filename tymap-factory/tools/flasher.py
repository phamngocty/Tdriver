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

PYTHON_EXE = sys.executable
ESPTOOL_PY = os.path.expanduser(r"~\.platformio\packages\tool-esptoolpy\esptool.py")

BIN_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "binaries"))

# Partition Flash Offsets for ESP32-C3 (4MB)
OFFSETS = {
    "bootloader": "0x0",
    "partitions": "0x8000",
    "factory":    "0x10000",
    "android":    "0x110000", # ota_0
    "ios":        "0x280000", # ota_1
}

FILES = {
    "bootloader": os.path.join(BIN_DIR, "bootloader.bin"),
    "partitions": os.path.join(BIN_DIR, "partitions.bin"),
    "factory":    os.path.join(BIN_DIR, "factory.bin"),
    "android":    os.path.join(BIN_DIR, "android.bin"),
    "ios":        os.path.join(BIN_DIR, "ios.bin"),
}

def auto_detect_port():
    try:
        import serial.tools.list_ports
        ports = list(serial.tools.list_ports.comports())
        for p in ports:
            desc = p.description.lower()
            if any(k in desc for k in ["esp", "usb-serial", "jtag", "ch340", "cp210", "uart"]):
                print(f"[*] Tim thay thiet bi ESP32 tren cong: {p.device} ({p.description})")
                return p.device
        if ports:
            print(f"[*] Chon cong COM mac dinh dau tien: {ports[0].device}")
            return ports[0].device
    except Exception as e:
        pass
    return "COM3"

def run_esptool(cmd_args):
    if os.path.exists(ESPTOOL_PY):
        cmd = [PYTHON_EXE, ESPTOOL_PY] + cmd_args
    else:
        cmd = ["esptool.py"] + cmd_args
    print("\n" + "="*60)
    print("[RUN COMMAND]:", " ".join(cmd))
    print("="*60 + "\n")
    return subprocess.call(cmd)

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

def main():
    action = "all"
    if len(sys.argv) > 1:
        action = sys.argv[1].lower()

    port = auto_detect_port()
    if len(sys.argv) > 2:
        port = sys.argv[2]

    if action == "all":
        sys.exit(flash_all(port))
    elif action in ["factory", "android", "ios", "bootloader", "partitions"]:
        sys.exit(flash_single(action, port))
    else:
        print("Cach su dung: python flasher.py [all|factory|android|ios] [PORT]")
        sys.exit(1)

if __name__ == "__main__":
    main()
