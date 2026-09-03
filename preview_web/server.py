import http.server
import socketserver
import urllib.request
import urllib.parse
import os
import sys

PORT = 57511
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class TileProxyHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        if self.path.startswith('/proxy_tile?url='):
            raw_url = self.path[len('/proxy_tile?url='):]
            target_url = urllib.parse.unquote(raw_url)
            try:
                headers = {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                }
                req = urllib.request.Request(target_url, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as response:
                    content = response.read()
                    content_type = response.headers.get('Content-Type', 'image/png')
                    self.send_response(200)
                    self.send_header('Content-Type', content_type)
                    self.send_header('Content-Length', str(len(content)))
                    self.send_header('Cache-Control', 'public, max-age=3600')
                    self.end_headers()
                    self.wfile.write(content)
            except Exception as e:
                self.send_response(502)
                self.end_headers()
                self.wfile.write(f"Error fetching tile: {e}".encode('utf-8'))
        elif self.path.startswith('/osm_vector_road?'):
            import gzip
            import io
            from PIL import Image, ImageDraw
            params = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            z = params.get('z', ['14'])[0]
            x = params.get('x', ['0'])[0]
            y = params.get('y', ['0'])[0]
            mvt_url = f"https://vector.openstreetmap.org/shortbread_v1/{z}/{x}/{y}.mvt"
            try:
                req = urllib.request.Request(mvt_url, headers={'User-Agent': 'Mozilla/5.0'})
                raw = urllib.request.urlopen(req, timeout=6).read()
                try: data = gzip.decompress(raw)
                except: data = raw

                def read_varint(buf, pos):
                    res, shift = 0, 0
                    while pos < len(buf):
                        b = buf[pos]; pos += 1
                        res |= (b & 0x7f) << shift
                        if not (b & 0x80): break
                        shift += 7
                    return res, pos

                pos = 0
                all_lines = []
                while pos < len(data):
                    tag, pos = read_varint(data, pos)
                    fn, wt = tag >> 3, tag & 0x7
                    if wt == 2:
                        length, pos = read_varint(data, pos)
                        val = data[pos:pos+length]
                        pos += length
                        if fn == 3: # layer
                            lpos = 0; layer_name = ''; extent = 4096; features = []
                            while lpos < len(val):
                                ltag, lpos = read_varint(val, lpos)
                                lfn, lwt = ltag >> 3, ltag & 0x7
                                if lwt == 2:
                                    llen, lpos = read_varint(val, lpos)
                                    lval = val[lpos:lpos+llen]; lpos += llen
                                    if lfn == 1: layer_name = lval.decode('latin1', 'ignore')
                                    elif lfn == 2: features.append(lval)
                                elif lwt == 0:
                                    lval, lpos = read_varint(val, lpos)
                                    if lfn == 5: extent = lval
                            if 'street' in layer_name or 'road' in layer_name:
                                for fval in features:
                                    fpos = 0; geom_type = 0; geom_cmds = []
                                    while fpos < len(fval):
                                        ftag, fpos = read_varint(fval, fpos)
                                        ffn, fwt = ftag >> 3, ftag & 0x7
                                        if fwt == 0:
                                            v, fpos = read_varint(fval, fpos)
                                            if ffn == 3: geom_type = v
                                        elif fwt == 2:
                                            flen, fpos = read_varint(fval, fpos)
                                            cdata = fval[fpos:fpos+flen]; fpos += flen
                                            if ffn == 4:
                                                cpos = 0
                                                while cpos < len(cdata):
                                                    gv, cpos = read_varint(cdata, cpos)
                                                    geom_cmds.append(gv)
                                    if geom_type == 2: # LineString
                                        ci = 0; curr = []; cx, cy = 0, 0
                                        while ci < len(geom_cmds):
                                            cmd_hdr = geom_cmds[ci]; cmd = cmd_hdr & 0x7; cnt = cmd_hdr >> 3; ci += 1
                                            if cmd == 1 or cmd == 2:
                                                for _ in range(cnt):
                                                    dx = (geom_cmds[ci] >> 1) ^ -(geom_cmds[ci] & 1)
                                                    dy = (geom_cmds[ci+1] >> 1) ^ -(geom_cmds[ci+1] & 1)
                                                    cx += dx; cy += dy
                                                    curr.append((cx * 256.0 / extent, cy * 256.0 / extent))
                                                    ci += 2
                                                if cmd == 1 and curr:
                                                    if len(curr) > 1: all_lines.append(curr)
                                                    curr = [curr[-1]]
                                        if curr and len(curr) > 1: all_lines.append(curr)

                img = Image.new('RGB', (256, 256), (0, 0, 0))
                draw = ImageDraw.Draw(img)
                for line in all_lines:
                    if len(line) >= 2:
                        draw.line(line, fill=(255, 255, 255), width=2)
                buf = io.BytesIO()
                img.save(buf, format='PNG')
                png_bytes = buf.getvalue()

                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Content-Length', str(len(png_bytes)))
                self.send_header('Cache-Control', 'public, max-age=3600')
                self.end_headers()
                self.wfile.write(png_bytes)
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(f"Error rendering MVT: {e}".encode('utf-8'))
        else:
            super().do_GET()

if __name__ == '__main__':
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), TileProxyHandler) as httpd:
        print(f"Server preview running at http://localhost:{PORT}")
        sys.stdout.flush()
        httpd.serve_forever()
