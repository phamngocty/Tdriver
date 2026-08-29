import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

casaos_conf = """# ------------------------------------------------------------
# CasaOS Direct IP Proxy (192.168.1.114)
# ------------------------------------------------------------

server {
    listen 80;
    server_name 192.168.1.114 localhost _;

    access_log /data/logs/casaos_access.log standard;
    error_log /data/logs/casaos_error.log warn;

    include conf.d/include/block-exploits.conf;

    location / {
        proxy_pass http://172.17.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $http_connection;
        proxy_http_version 1.1;
        proxy_read_timeout 900s;
        proxy_send_timeout 900s;
    }
}
"""

cmd = f"echo 271000 | sudo -S bash -c 'cat << \\'EOF\\' > /home/nas152/tymap_data/npm/data/nginx/proxy_host/casaos.conf\n{casaos_conf}\nEOF'"
stdin, stdout, stderr = ssh.exec_command(cmd)
print('Write status:', stdout.read().decode('utf-8', errors='ignore'))

# Reload NPM
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -t && echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
print('=== NGINX TEST & RELOAD ===')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

# Test curl with Host: 192.168.1.114
stdin, stdout, stderr = ssh.exec_command('curl -s -H "Host: 192.168.1.114" http://127.0.0.1:80/ | grep -i title')
print('=== CURL WITH HOST 192.168.1.114 ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
