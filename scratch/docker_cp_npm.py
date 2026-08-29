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

# Upload index.html to /home/nas152/casaos_index.html
sftp = ssh.open_sftp()
with sftp.open('/home/nas152/casaos_index.html', 'w') as f:
    f.write(redirect_html)
sftp.close()

# Docker cp into tymap_npm
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker cp /home/nas152/casaos_index.html tymap_npm:/var/www/html/index.html')
stdout.read()

# Also update /etc/nginx/conf.d/default.conf via docker cp!
default_conf = """server {
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

sftp = ssh.open_sftp()
with sftp.open('/home/nas152/casaos_default.conf', 'w') as f:
    f.write(default_conf)
sftp.close()

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker cp /home/nas152/casaos_default.conf tymap_npm:/etc/nginx/conf.d/default.conf')
stdout.read()

# Reload nginx
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
stdout.read()

# Test curl
stdin, stdout, stderr = ssh.exec_command('curl -s http://127.0.0.1:80/')
print('=== CURL RESULT ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
