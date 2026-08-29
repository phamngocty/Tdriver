import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

default_conf = """server {
	listen 80 default_server;
	server_name _;

	access_log /data/logs/fallback_http_access.log standard;
	error_log /data/logs/fallback_http_error.log warn;
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
		proxy_send_timeout 900s;
		proxy_buffering off;
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
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -t && echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
