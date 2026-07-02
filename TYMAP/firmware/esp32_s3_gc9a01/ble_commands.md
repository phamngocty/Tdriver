# TYMAP BLE Protocol Specification


This document details the BLE characteristics and data formats used for communication between the TYMAP Android app and the ESP32-S3 firmware.


## Service UUID

`0000feed-0000-1000-8000-00805f9b34fb`


## Characteristics


| Name | UUID | Direction | Format | Description |

| :--- | :--- | :--- | :--- | :--- |

| **CHA_NAV** | `0b11deef-1563-447f-aece-d3dfeb1c1f20` | App → ESP | Text (`key=value\n`) | Navigation data (dist, title, dir, eta, ete). |

| **CHA_NAV_TBT_ICON** | `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` | App → ESP | Mixed | `hash=<hex>`, 1-byte index, or 288-byte bitmap. |

| **CHA_ICON_DATA** | `e2f3a4b5-c6d7-4890-e123-456789abcdef` | App → ESP | Binary | 4-byte hash (Little Endian) + 288-byte bitmap (1-bit, 48x48). |

| **CHA_GPS_SPEED** | `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` | App → ESP | Text | Current vehicle speed (km/h). |

| **CHA_SETTINGS** | `9d37a346-63d3-4df6-8eee-f0242949f59f` | App → ESP | Text (`key=value\n`) | System settings (brightness, popupEnabled, popupDuration, hudTimeout). |

| **CHA_TIME** | `a1b2c3d4-e5f6-4789-a012-3456789abcde` | App → ESP | Binary | 4-byte Unix Timestamp (Little Endian). |

| **CHA_WEATHER** | `b2c3d4e5-f6a7-4890-b123-456789abcdef` | App → ESP | JSON | Current temperature (`t`) and weather code (`i`). |

| **CHA_MAP_IMAGE** | `c3d4e5f6-a7b8-4901-c234-567890abcdef` | App → ESP | Binary (Chunks) | Map screenshot (JPEG). First packet is 4-byte size. |

| **CHA_OLED_IMAGE** | `e1f2a3b4-c5d6-4789-a012-3456789abcdef` | App → ESP | Binary (Chunks) | 1-bit monochrome image for OLED displays. First packet is 2-byte size. |

| **CHA_NOTIFICATION** | `c1d2e3f4-a5b6-4789-c012-3456789abcde` | App → ESP | JSON | Smartphone notifications (app, title, message). |

| **CHA_DEVICE_CTRL** | `d4e5f6a7-b8c9-4012-d345-678901bcdef0` | ESP ↔ App | Byte (Bitfield) | ESP control: bit0=ZoomIn, bit1=ZoomOut, bit2=MapMode, bit3=ExitPopup. |

| **CHA_REMOTE_CMD** | `f1a2b3c4-d5e6-4789-a012-3456789abcde` | App → ESP | Byte | 0x10=HUD, 0x11=MAP, 0x12=STATUS, 0x13=INFO, 0x14=NOTIF, 0x20=ReqStatus, 0x30=Ping, 0xFF=Restart. |

| **CHA_DEVICE_STATUS** | `a1b2c3d4-e5f6-4789-b012-3456789abcde` | ESP → App | Text (`key=value\n`) | Device info (mode, voltage, rssi, display). Also used for `icon_req=<hash>`. |

| **CHA_PHONE_BATTERY** | `e5f6a7b8-c9d0-4123-e456-789012cdef01` | App → ESP | JSON | Phone battery level (`level`) and charging state (`charging`). |


## Command Data Formats


### Navigation (CHA_NAV)

Multi-line string format:

```

dist=100 m

title=Rẽ trái

road=Đường Phan Văn Mãng

eta=12:30

```


### Settings (CHA_SETTINGS)

```

brightness=80

popupDuration=5

hudTimeout=10

```


### Notifications (CHA_NOTIFICATION)

```json

{

  "app": "Zalo",

  "title": "Nguyễn Văn A",

  "message": "Chiều nay đi cafe không?"

}

```


