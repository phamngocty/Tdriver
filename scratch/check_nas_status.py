import paramiko
import sys

sys.stdout.reconfigure(encoding='utf-8')

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

test_cmds = [
    ("NPM Web Dashboard (81)", "curl -s -o /dev/null -w '%{http_code}' http://localhost:81"),
    ("NPM HTTP Entry (80)", "curl -s -o /dev/null -w '%{http_code}' http://localhost:80"),
    ("Fusion Engine Health (8088)", "curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/health"),
    ("Fusion Engine Route Warnings (8088)", "curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8088/api/warnings/route -H 'Content-Type: application/json' -d '{\"polyline\":[[10.76,106.66],[10.77,106.67]]}'"),
    ("GraphHopper Health (8989)", "curl -s -o /dev/null -w '%{http_code}' http://localhost:8989/health"),
    ("Nominatim Status (8081)", "curl -s -o /dev/null -w '%{http_code}' 'http://localhost:8081/status?format=json'")
]

print("=== KIỂM TRA TRẠNG THÁI CÁC DỊCH VỤ TRÊN NAS ===")
for name, cmd in test_cmds:
    stdin, stdout, stderr = ssh.exec_command(cmd)
    code = stdout.read().decode('utf-8').strip()
    status = "✅ ONLINE (200 OK)" if code in ['200', '302', '301'] else f"⚠️ CODE: {code}"
    print(f"  * {name:<35}: {status}")

ssh.close()
