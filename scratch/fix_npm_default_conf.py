import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

new_default_conf = """# "You are not configured" page forwarded directly to CasaOS
server {
	listen 80 default_server;
	#listen [::]:80 default_server;

	set $forward_scheme "http";
	set $server "172.17.0.1";
	set $port "8090";

	server_name _;
	access_log /data/logs/fallback_http_access.log standard;
	error_log /data/logs/fallback_http_error.log warn;
	include conf.d/include/assets.conf;
	include conf.d/include/block-exploits.conf;
	include conf.d/include/letsencrypt-acme-challenge.conf;

	location / {
		proxy_pass http://172.17.0.1:8090;
		proxy_set_header Host $host;
		proxy_set_header X-Real-IP $remote_addr;
		proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
		proxy_set_header X-Forwarded-Proto $scheme;
		proxy_set_header Upgrade $http_upgrade;
		proxy_set_header Connection $http_connection;
		proxy_http_version 1.1;
	}
}

# First 443 Host, which is the default if another default doesn't exist
server {
	listen 443 ssl default_server;
	#listen [::]:443 ssl default_server;

	set $forward_scheme "https";
	set $server "127.0.0.1";
	set $port "443";

	server_name _;
	access_log /data/logs/fallback_http_access.log standard;
	error_log /dev/null crit;
	include conf.d/include/ssl-ciphers.conf;
	ssl_reject_handshake on;

	return 444;
}
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm bash -c 'cat << \\'EOF\\' > /etc/nginx/conf.d/default.conf\n{new_default_conf}\nEOF'"
stdin, stdout, stderr = ssh.exec_command(cmd)
print('Write default.conf:', stdout.read().decode('utf-8', errors='ignore'))

# Reload NPM nginx
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -t && echo 271000 | sudo -S docker exec tymap_npm nginx -s reload')
print('=== NGINX TEST & RELOAD ===')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

ssh.close()
