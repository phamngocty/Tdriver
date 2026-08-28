const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8088;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');

// Types: 1 = Speed camera (Camera phạt nguội), 2 = Speed limit (Biển tốc độ)
const TYPE_CAMERA = 1;
const TYPE_SPEED_LIMIT = 2;

let warningPoints = [];
let lastLoadedTime = 0;

/**
 * Calculates Haversine distance in meters between two lat/lon coordinates
 */
function haversineDistance(lat1, lon1, lat2, lon2) {
    const R = 6371000; // meters
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

/**
 * Distance from point P to line segment AB in meters
 */
function distancePointToSegment(pLat, pLon, aLat, aLon, bLat, bLon) {
    const segmentLength = haversineDistance(aLat, aLon, bLat, bLon);
    if (segmentLength === 0) return haversineDistance(pLat, pLon, aLat, aLon);

    // Flat approximation projection for short distances
    const dLat = bLat - aLat;
    const dLon = bLon - aLon;
    const u = ((pLat - aLat) * dLat + (pLon - aLon) * dLon) / (dLat * dLat + dLon * dLon);

    if (u < 0) {
        return haversineDistance(pLat, pLon, aLat, aLon);
    } else if (u > 1) {
        return haversineDistance(pLat, pLon, bLat, bLon);
    } else {
        const projLat = aLat + u * dLat;
        const projLon = aLon + u * dLon;
        return haversineDistance(pLat, pLon, projLat, projLon);
    }
}

/**
 * Parse CSV line handling potential quotes
 */
function parseCsvLine(line) {
    const values = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i++) {
        const char = line[i];
        if (char === '"') {
            inQuotes = !inQuotes;
        } else if (char === ',' && !inQuotes) {
            values.push(current.trim());
            current = '';
        } else {
            current += char;
        }
    }
    values.push(current.trim());
    return values;
}

/**
 * Read and parse CSV file
 */
function readCsvFile(filePath, defaultType = TYPE_CAMERA) {
    if (!fs.existsSync(filePath)) {
        console.warn(`[FusionEngine] File not found: ${filePath}`);
        return [];
    }

    const content = fs.readFileSync(filePath, 'utf8');
    const lines = content.split(/\r?\n/).filter(l => l.trim().length > 0);
    if (lines.length <= 1) return [];

    const headers = parseCsvLine(lines[0].toLowerCase());
    const latIdx = headers.findIndex(h => h.includes('lat'));
    const lonIdx = headers.findIndex(h => h.includes('lon') || h.includes('lng'));
    const speedIdx = headers.findIndex(h => h.includes('speed') || h.includes('toc_do') || h.includes('limit'));
    const typeIdx = headers.findIndex(h => h.includes('type') || h.includes('loai'));
    const idIdx = headers.findIndex(h => h === 'id' || h.includes('id'));
    const descIdx = headers.findIndex(h => h.includes('desc') || h.includes('name') || h.includes('ghi_chu'));

    const items = [];
    for (let i = 1; i < lines.length; i++) {
        const cols = parseCsvLine(lines[i]);
        if (cols.length <= Math.max(latIdx, lonIdx)) continue;

        const lat = parseFloat(cols[latIdx]);
        const lon = parseFloat(cols[lonIdx]);
        if (isNaN(lat) || isNaN(lon)) continue;

        let speed = 0;
        if (speedIdx !== -1 && cols[speedIdx]) {
            speed = parseInt(cols[speedIdx].replace(/[^0-9]/g, ''), 10) || 0;
        }

        let type = defaultType;
        if (typeIdx !== -1 && cols[typeIdx]) {
            const rawType = cols[typeIdx].toLowerCase();
            if (rawType.includes('cam') || rawType === '1') type = TYPE_CAMERA;
            else if (rawType.includes('speed') || rawType.includes('toc_do') || rawType === '2') type = TYPE_SPEED_LIMIT;
        }

        const id = (idIdx !== -1 && cols[idIdx]) ? cols[idIdx] : `pt_${i}_${lat.toFixed(5)}_${lon.toFixed(5)}`;
        const description = (descIdx !== -1 && cols[descIdx]) ? cols[descIdx] : '';

        items.push({ id, lat, lon, type, speedLimit: speed, description });
    }
    return items;
}

/**
 * Deduplicate spatial points within radius threshold (e.g., 25 meters)
 */
function spatialDeduplicate(points, radiusMeters = 25) {
    const results = [];
    const visited = new Set();

    for (let i = 0; i < points.length; i++) {
        if (visited.has(i)) continue;
        const current = points[i];
        visited.add(i);

        let bestPoint = { ...current };
        // Check for duplicates
        for (let j = i + 1; j < points.length; j++) {
            if (visited.has(j)) continue;
            const other = points[j];
            const dist = haversineDistance(current.lat, current.lon, other.lat, other.lon);
            if (dist <= radiusMeters) {
                visited.add(j);
                // Merge info: prioritize higher speed limit or richer description
                if (other.speedLimit > bestPoint.speedLimit) {
                    bestPoint.speedLimit = other.speedLimit;
                }
                if (!bestPoint.description && other.description) {
                    bestPoint.description = other.description;
                }
            }
        }
        results.push(bestPoint);
    }
    return results;
}

/**
 * Reload and merge all datasets
 */
function reloadData() {
    console.log(`[FusionEngine] Reloading datasets from ${DATA_DIR}...`);
    const osmFile = path.join(DATA_DIR, 'osm_cameras.csv');
    const customFile = path.join(DATA_DIR, 'camera_vn.csv');

    const osmPoints = readCsvFile(osmFile, TYPE_CAMERA);
    const customPoints = readCsvFile(customFile, TYPE_CAMERA);

    const merged = [...osmPoints, ...customPoints];
    warningPoints = spatialDeduplicate(merged, 25);
    lastLoadedTime = Date.now();

    console.log(`[FusionEngine] Loaded ${osmPoints.length} OSM + ${customPoints.length} Custom -> ${warningPoints.length} deduplicated warning points.`);
}

// Initial load
reloadData();

const server = http.createServer((req, res) => {
    // CORS headers
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, User-Agent');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    const url = new URL(req.url, `http://${req.headers.host}`);

    if (url.pathname === '/health' && req.method === 'GET') {
        const mem = process.memoryUsage();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'ok',
            pointsCount: warningPoints.length,
            lastLoaded: new Date(lastLoadedTime).toISOString(),
            memory: {
                rssMb: (mem.rss / 1024 / 1024).toFixed(2),
                heapUsedMb: (mem.heapUsed / 1024 / 1024).toFixed(2)
            }
        }));
        return;
    }

    if (url.pathname === '/api/reload' && req.method === 'POST') {
        reloadData();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'reloaded', count: warningPoints.length }));
        return;
    }

    if (url.pathname === '/api/warnings/nearby' && req.method === 'GET') {
        const lat = parseFloat(url.searchParams.get('lat'));
        const lon = parseFloat(url.searchParams.get('lon'));
        const radius = parseFloat(url.searchParams.get('radius')) || 2000; // meters

        if (isNaN(lat) || isNaN(lon)) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Missing or invalid lat/lon parameters' }));
            return;
        }

        const nearby = warningPoints.filter(pt => {
            const dist = haversineDistance(lat, lon, pt.lat, pt.lon);
            return dist <= radius;
        }).map(pt => ({
            ...pt,
            distMeters: Math.round(haversineDistance(lat, lon, pt.lat, pt.lon))
        }));

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            center: { lat, lon },
            radiusMeters: radius,
            count: nearby.length,
            points: nearby
        }));
        return;
    }

    if (url.pathname === '/api/warnings/route' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
            try {
                const data = JSON.parse(body);
                // Support coordinates as [[lon, lat], ...] or polyline as [[lat, lon], ...]
                let polyline = [];
                if (Array.isArray(data.polyline)) {
                    polyline = data.polyline; // [[lat, lon], ...]
                } else if (Array.isArray(data.coordinates)) {
                    // GeoJSON coordinates [[lon, lat], ...]
                    polyline = data.coordinates.map(c => [c[1], c[0]]);
                }

                const bufferMeters = data.bufferMeters || 50; // default 50m corridor

                if (polyline.length === 0) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Empty polyline or coordinates' }));
                    return;
                }

                // Compute bounding box with margin
                let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
                for (const [lat, lon] of polyline) {
                    if (lat < minLat) minLat = lat;
                    if (lat > maxLat) maxLat = lat;
                    if (lon < minLon) minLon = lon;
                    if (lon > maxLon) maxLon = lon;
                }
                const marginDeg = bufferMeters / 111000; // ~meters to degrees
                minLat -= marginDeg; maxLat += marginDeg;
                minLon -= marginDeg; maxLon += marginDeg;

                // 1. Fast Bounding Box Pre-filter
                const bboxCandidates = warningPoints.filter(pt =>
                    pt.lat >= minLat && pt.lat <= maxLat &&
                    pt.lon >= minLon && pt.lon <= maxLon
                );

                // 2. Accurate corridor check against polyline segments
                const matchedPoints = [];
                for (const pt of bboxCandidates) {
                    let minDistance = Infinity;
                    for (let i = 0; i < polyline.length - 1; i++) {
                        const a = polyline[i];
                        const b = polyline[i + 1];
                        const d = distancePointToSegment(pt.lat, pt.lon, a[0], a[1], b[0], b[1]);
                        if (d < minDistance) {
                            minDistance = d;
                            if (minDistance <= bufferMeters) break; // early exit for this segment
                        }
                    }
                    if (minDistance <= bufferMeters) {
                        matchedPoints.push({
                            id: pt.id,
                            type: pt.type,
                            speedLimit: pt.speedLimit,
                            lat: pt.lat,
                            lon: pt.lon,
                            description: pt.description,
                            distToRouteMeters: Math.round(minDistance)
                        });
                    }
                }

                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    bufferMeters,
                    count: matchedPoints.length,
                    points: matchedPoints
                }));
            } catch (err) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: `Invalid JSON body: ${err.message}` }));
            }
        });
        return;
    }

    if (url.pathname === '/api/warnings/all' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            count: warningPoints.length,
            points: warningPoints
        }));
        return;
    }

    // Overpass QL Compatibility Interpreter Endpoint (For SpeedLimitEngine & MapFragment)
    if (url.pathname === '/api/interpreter' || url.pathname === '/interpreter') {
        const handleInterpreterQuery = (queryStr) => {
            try {
                const matchedPoints = new Set();

                // 1. Match around:radius,lat,lon patterns
                const aroundRegex = /around:(\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/g;
                let match;
                while ((match = aroundRegex.exec(queryStr)) !== null) {
                    const radius = parseFloat(match[1]);
                    const lat = parseFloat(match[2]);
                    const lon = parseFloat(match[3]);

                    for (const pt of warningPoints) {
                        const d = haversineDistance(lat, lon, pt.lat, pt.lon);
                        if (d <= radius) {
                            matchedPoints.add(pt);
                        }
                    }
                }

                // 2. Match bounding box (south, west, north, east) patterns
                const bboxRegex = /\((-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)\)/g;
                while ((match = bboxRegex.exec(queryStr)) !== null) {
                    const s = parseFloat(match[1]);
                    const w = parseFloat(match[2]);
                    const n = parseFloat(match[3]);
                    const e = parseFloat(match[4]);

                    for (const pt of warningPoints) {
                        if (pt.lat >= s && pt.lat <= n && pt.lon >= w && pt.lon <= e) {
                            matchedPoints.add(pt);
                        }
                    }
                }

                const elements = Array.from(matchedPoints).map((pt, idx) => {
                    const tags = {};
                    if (pt.type === TYPE_CAMERA) {
                        tags["highway"] = "speed_camera";
                    }
                    if (pt.speedLimit > 0) {
                        tags["maxspeed"] = pt.speedLimit.toString();
                    }
                    if (pt.description) {
                        tags["name"] = pt.description;
                    }
                    return {
                        type: "node",
                        id: pt.id ? parseInt(pt.id) || (idx + 1) : (idx + 1),
                        lat: pt.lat,
                        lon: pt.lon,
                        tags: tags
                    };
                });

                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    version: 0.6,
                    generator: "FusionEngine/1.0",
                    elements: elements
                }));
            } catch (err) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            }
        };

        if (req.method === 'GET') {
            const queryData = url.searchParams.get('data') || '';
            handleInterpreterQuery(queryData);
            return;
        } else if (req.method === 'POST') {
            let body = '';
            req.on('data', chunk => { body += chunk; });
            req.on('end', () => {
                let queryStr = body;
                if (body.startsWith('data=')) {
                    try {
                        queryStr = decodeURIComponent(body.substring(5).replace(/\+/g, ' '));
                    } catch (e) {
                        queryStr = body.substring(5);
                    }
                }
                handleInterpreterQuery(queryStr);
            });
            return;
        }
    }

    // 6. Version & OTA Updates API (/version.json or /api/version)
    if (url.pathname === '/version.json' || url.pathname === '/api/version') {
        const versionFilePath = path.join(DATA_DIR, 'version.json');
        if (fs.existsSync(versionFilePath)) {
            try {
                const data = fs.readFileSync(versionFilePath, 'utf8');
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(data);
                return;
            } catch (err) {
                console.error(`[FusionEngine] Error reading version.json: ${err.message}`);
            }
        }

        // Fallback default version schema
        const defaultVersion = {
            app: {
                versionCode: 1,
                versionName: "1.0.0",
                apkUrl: "https://git.nas152.duckdns.org/nas152/TYMAP/releases/download/v1.0.0/app-debug.apk",
                changelog: "Phiên bản khởi tạo chính thức từ máy chủ NAS152."
            },
            firmware: {
                versionCode: 1,
                versionName: "1.0.0",
                binUrl: "https://git.nas152.duckdns.org/nas152/TYMAP/releases/download/v1.0.0/firmware.bin",
                changelog: "Firmware ESP32 chuẩn tối ưu hóa hiển thị OLED/TFT."
            }
        };
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(defaultVersion, null, 2));
        return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Endpoint not found' }));
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`[FusionEngine] Server running on http://0.0.0.0:${PORT}`);
});
