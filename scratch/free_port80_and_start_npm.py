import paramiko
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

NAS_IP = '192.168.1.114'
NAS_USER = 'nas152'
NAS_PASS = '271000'

def run():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"[*] Connecting to {NAS_USER}@{NAS_IP}...", flush=True)
    ssh.connect(NAS_IP, username=NAS_USER, password=NAS_PASS, timeout=10)

    # 1. Update CasaOS gateway port to 8090
    print("[1] Changing CasaOS port from 80 to 8090 to free Port 80 for NPM...", flush=True)
    stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S sed -i "s/port=80$/port=8090/" /etc/casaos/gateway.ini')
    stdout.read()
    stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S systemctl restart casaos-gateway')
    stdout.read()
    time.sleep(3)

    # 2. Check Port 80 status
    print("[2] Verifying Port 80 is free...", flush=True)
    stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S ss -tulpn')
    out = stdout.read().decode('utf-8', errors='replace')
    p80_used = False
    for line in out.splitlines():
        if ':80 ' in line or ':8090 ' in line:
            print("  ", line, flush=True)
            if ':80 ' in line:
                p80_used = True

    # 3. Start NPM and all TYMAP services
    print("\n[3] Starting tymap_npm & services via Docker Compose...", flush=True)
    stdin, stdout, stderr = ssh.exec_command('cd /home/nas152/graphhopper-data && docker compose up -d --remove-orphans')
    print("OUTPUT:", stdout.read().decode('utf-8', errors='replace'), flush=True)
    print("ERRORS:", stderr.read().decode('utf-8', errors='replace'), flush=True)

    time.sleep(5)

    # 4. Check container list
    print("\n[4] Docker Container Status:", flush=True)
    stdin, stdout, stderr = ssh.exec_command('docker ps --filter "name=tymap" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"')
    print(stdout.read().decode('utf-8', errors='replace'), flush=True)

    # 5. Check endpoints
    print("\n[5] Testing Local Health Endpoints:", flush=True)
    test_cmds = [
        "curl -s -o /dev/null -w 'NPM Web UI (81): %{http_code}\n' http://localhost:81",
        "curl -s -o /dev/null -w 'NPM Port 80: %{http_code}\n' http://localhost:80",
        "curl -s -o /dev/null -w 'Fusion Engine (8088): %{http_code}\n' http://localhost:8088/health",
        "curl -s -o /dev/null -w 'GraphHopper (8989): %{http_code}\n' http://localhost:8989/health"
    ]
    for tc in test_cmds:
        stdin, stdout, stderr = ssh.exec_command(tc)
        print("  ", stdout.read().decode('utf-8', errors='replace').strip(), flush=True)

    ssh.close()
    print("\n[+] Done!")

if __name__ == '__main__':
    run()
