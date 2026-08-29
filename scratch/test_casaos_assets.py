import urllib.request
import re

req = urllib.request.Request('http://192.168.1.114/', headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req).read().decode('utf-8', errors='ignore')
print('HTML from 192.168.1.114:')
print(html)

assets = re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"', html)
print('\nAsset files found:', assets)
for a in assets:
    url = 'http://192.168.1.114' + a if a.startswith('/') else 'http://192.168.1.114/' + a
    try:
        r = urllib.request.urlopen(url)
        print(f'Asset {url} -> Status {r.status}, size {len(r.read())}')
    except Exception as e:
        print(f'Asset {url} -> ERROR {e}')
