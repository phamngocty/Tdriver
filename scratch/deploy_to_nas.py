import paramiko
import sys

sys.stdout.reconfigure(encoding='utf-8')

NAS_IP = '192.168.1.114'
NAS_USER = 'nas152'
NAS_PASS = '271000'

def deploy():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"[*] Connecting to {NAS_USER}@{NAS_IP}...", flush=True)
    ssh.connect(NAS_IP, username=NAS_USER, password=NAS_PASS, timeout=10)
    print("[+] Connected successfully!", flush=True)

    # 1. Stop existing photon container if broken
    print("\n--- 1. Cleaning up broken photon container if any ---", flush=True)
    stdin, stdout, stderr = ssh.exec_command('docker stop tymap_photon || true; docker rm tymap_photon || true')
    print(stdout.read().decode('utf-8', errors='replace'), flush=True)

    # 2. Upload files
    print("\n--- 2. Uploading latest configuration & fusion_engine code ---", flush=True)
    sftp = ssh.open_sftp()
    
    local_compose = r'd:\Documents\PlatformIO\Tdriver\nas_services\docker-compose.yml'
    remote_compose = '/home/nas152/graphhopper-data/docker-compose.yml'
    sftp.put(local_compose, remote_compose)
    print(f"  [✓] Uploaded {remote_compose}", flush=True)

    local_server_js = r'd:\Documents\PlatformIO\Tdriver\nas_services\fusion_engine\server.js'
    remote_server_js = '/home/nas152/graphhopper-data/fusion_engine/server.js'
    sftp.put(local_server_js, remote_server_js)
    print(f"  [✓] Uploaded {remote_server_js}", flush=True)

    sftp.close()

    # 3. Ensure tymap_net exists
    print("\n--- 3. Ensuring Docker Network tymap_net ---", flush=True)
    stdin, stdout, stderr = ssh.exec_command('docker network inspect tymap_net >/dev/null 2>&1 || docker network create tymap_net')
    print(stdout.read().decode('utf-8', errors='replace'), flush=True)

    # 4. Restart Docker Compose with rebuild
    print("\n--- 4. Running Docker Compose Up ---", flush=True)
    stdin, stdout, stderr = ssh.exec_command('cd /home/nas152/graphhopper-data && docker compose up -d --build --remove-orphans')
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    print("OUTPUT:", out, flush=True)
    if err:
        print("ERRORS/NOTICES:", err, flush=True)

    # 5. Check all containers
    print("\n--- 5. Checking container status ---", flush=True)
    stdin, stdout, stderr = ssh.exec_command('docker ps --filter "name=tymap" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"')
    print(stdout.read().decode('utf-8', errors='replace'), flush=True)

    # 6. Test HTTP endpoints
    print("\n--- 6. Testing local endpoints ---", flush=True)
    test_cmds = [
        "curl -s -o /dev/null -w 'NPM Web UI (81): %{http_code}\n' http://localhost:81 || true",
        "curl -s -o /dev/null -w 'GraphHopper (8989): %{http_code}\n' http://localhost:8989/health || true",
        "curl -s -o /dev/null -w 'Nominatim (8081): %{http_code}\n' 'http://localhost:8081/status?format=json' || true",
        "curl -s -o /dev/null -w 'Fusion Engine (8088): %{http_code}\n' http://localhost:8088/health || true"
    ]
    for tc in test_cmds:
        stdin, stdout, stderr = ssh.exec_command(tc)
        print("  ", stdout.read().decode('utf-8', errors='replace').strip(), flush=True)

    ssh.close()
    print("\n[+] All tasks completed successfully on NAS!", flush=True)

if __name__ == '__main__':
    deploy()
