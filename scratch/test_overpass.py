import urllib.request
import urllib.parse
import json
import time

lat, lon = 10.7769, 106.7009
d = 0.002 # ~220m radius
s, w, n, e = lat - d, lon - d, lat + d, lon + d

query = f"""[out:json][timeout:3];
(
  way["highway"~"motorway|trunk|primary|secondary|tertiary|residential"]({s},{w},{n},{e});
);
out skel qt geom;"""

url = "https://overpass-api.de/api/interpreter?data=" + urllib.parse.quote(query)
headers = {"User-Agent": "TYMAP-OLED-Vector/1.0"}

t0 = time.time()
try:
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=4) as res:
        raw = res.read()
        elapsed = time.time() - t0
        data = json.loads(raw.decode('utf-8'))
        ways = [el for el in data.get("elements", []) if el.get("type") == "way"]
        print(f"FAST QUERY OK: {elapsed:.2f}s | Roads: {len(ways)} | Size: {len(raw)/1024:.1f} KB")
except Exception as exc:
    print("Error:", exc)

for ep in endpoints:
    url = ep + "?data=" + urllib.parse.quote(query)
    t0 = time.time()
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read()
            elapsed = time.time() - t0
            data = json.loads(raw.decode('utf-8'))
            ways = [el for el in data.get("elements", []) if el.get("type") == "way"]
            print(f"[{ep}] OK ({elapsed:.2f}s) | Roads: {len(ways)} | Size: {len(raw)/1024:.1f} KB")
            break
    except Exception as exc:
        print(f"[{ep}] Error: {exc}")
