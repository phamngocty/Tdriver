import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

redirect_html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta http-equiv="refresh" content="0; url=http://192.168.1.114:8090/">
    <script>
        var target = 'http://' + window.location.hostname + ':8090/';
        window.location.replace(target);
    </script>
    <title>CasaOS</title>
</head>
<body style="background:#1a1b1e;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
    <div style="text-align:center;">
        <h2>Đang chuyển hướng tới CasaOS...</h2>
        <p><a href="http://192.168.1.114:8090/" style="color:#00aaff;">Bấm vào đây nếu không tự chuyển</a></p>
    </div>
</body>
</html>
"""

# Replace /var/www/html/index.html inside NPM
cmd1 = f"echo 271000 | sudo -S docker exec -i tymap_npm bash -c 'cat << \\'EOF\\' > /var/www/html/index.html\n{redirect_html}\nEOF'"
stdin, stdout, stderr = ssh.exec_command(cmd1)
print('Replace index.html:', stdout.read().decode('utf-8', errors='ignore'))

# Also update /etc/nginx/conf.d/default.conf
new_default_conf = """server {
	listen 80 default_server;
	server_name _;

	access_log /data/logs/fallback_http_access.log standard;
	error_log /data/logs/fallback_http_error.log warn;
	include conf.d/include/assets.conf;
	include conf.d/include/block-exploits.conf;
	include conf.d/include/letsencrypt-acme-challenge.conf;

	location / {
		proxy_pass http://192.168.1.114:8090;
		proxy_set_header Host $host;
		proxy_set_header X-Real-IP $remote_addr;
		proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
		proxy_set_header X-Forwarded-Proto $scheme;
		proxy_set_header Upgrade $http_upgrade;
		proxy_set_header Connection $http_connection;
		proxy_http_version 1.1;
		proxy_read_timeout 900s;
	}
}

server {
	listen 443 ssl default_server;
	server_name _;

	access_log /data/logs/fallback_http_access.log standard;
	error_log /dev/null crit;
	include conf.d/include/ssl-ciphers.conf;
	ssl_reject_handshake on;

	return 444;
}
"""
cmd2 = f"echo 271000 | sudo -S docker exec -i tymap_npm bash -c 'cat << \\'EOF\\' > /etc/nginx/conf.d/default.conf\n{new_default_conf}\nEOF'"
stdin, stdout, stderr = ssh.exec_command(cmd2)
print('Update default.conf:', stdout.read().decode('utf-8', errors='ignore'))

# Reload NPM
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -t && echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
print('=== NGINX RELOAD ===')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

ssh.close()
