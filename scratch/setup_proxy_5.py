import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

conf_5 = """# ------------------------------------------------------------
# CasaOS Direct IP Proxy (192.168.1.114)
# ------------------------------------------------------------

server {
  set $forward_scheme http;
  set $server         "172.17.0.1";
  set $port           8090;

  listen 80;
  server_name 192.168.1.114;
  http2 off;

  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection $http_connection;
  proxy_http_version 1.1;

  access_log /data/logs/proxy-host-5_access.log proxy;
  error_log /data/logs/proxy-host-5_error.log warn;

  location / {
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $http_connection;
    proxy_http_version 1.1;

    # Proxy!
    include conf.d/include/proxy.conf;
  }
}
"""

cmd = f"echo 271000 | sudo -S bash -c 'cat << \\'EOF\\' > /home/nas152/tymap_data/npm/data/nginx/proxy_host/5.conf\n{conf_5}\nEOF'"
stdin, stdout, stderr = ssh.exec_command(cmd)

# Reload NPM nginx
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -t && echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
print('=== NGINX TEST & RELOAD ===')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

# Test curl
stdin, stdout, stderr = ssh.exec_command('curl -s -H "Host: 192.168.1.114" http://127.0.0.1:80/ | head -n 25')
print('=== CURL FROM NAS ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
