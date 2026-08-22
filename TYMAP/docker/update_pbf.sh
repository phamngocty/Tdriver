#!/bin/bash
set -e

# ==============================================================================
# Script Cập Nhật Dữ Liệu OSM PBF Việt Nam Cho TYMAP Master Data (NAS 192.168.1.114)
# Nguồn: Geofabrik Asia / Vietnam
# ==============================================================================

DATA_DIR="/home/nas152/tymap_data"
OSM_DIR="$DATA_DIR/osm_source"
PBF_URL="https://download.geofabrik.de/asia/vietnam-latest.osm.pbf"
MD5_URL="https://download.geofabrik.de/asia/vietnam-latest.osm.pbf.md5"

echo "=== [1/4] Tạo thư mục dữ liệu nếu chưa tồn tại ==="
mkdir -p "$OSM_DIR"
mkdir -p "$DATA_DIR/graphhopper_cache"
mkdir -p "$DATA_DIR/nominatim_db"
mkdir -p "$DATA_DIR/overpass_db"
mkdir -p "$DATA_DIR/tileserver_data"

cd "$OSM_DIR"

echo "=== [2/4] Tải file PBF và MD5 Checksum mới nhất từ Geofabrik ==="
wget -N "$MD5_URL"
wget -N "$PBF_URL"

echo "=== [3/4] Kiểm tra tính toàn vẹn (MD5 Checksum) ==="
if md5sum -c vietnam-latest.osm.pbf.md5; then
    echo "✅ File PBF hợp lệ và nguyên vẹn."
else
    echo "❌ Lỗi: Checksum không khớp, vui lòng tải lại."
    exit 1
fi

echo "=== [4/4] Cập nhật bộ nhớ đệm cho GraphHopper ==="
# Xóa cache cũ của GraphHopper để build lại ma trận đường đi với bản đồ mới
rm -rf "$DATA_DIR/graphhopper_cache/*"

# Khởi động lại container GraphHopper để nạp dữ liệu mới
if docker ps -a | grep -q "tymap_graphhopper"; then
    echo "Khởi động lại tymap_graphhopper..."
    docker restart tymap_graphhopper
fi

echo "🎉 Hoàn tất cập nhật Master Data PBF cho TYMAP!"
