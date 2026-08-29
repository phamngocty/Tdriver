import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm ls -la /var/www/html/')
print('=== /var/www/html/ in tymap_npm ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /data/nginx/custom/http_top.conf 2>/dev/null || true')
print('=== custom/http_top.conf ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -T')
print('=== FULL NGINX CONFIG DUMP ===')
dump = stdout.read().decode('utf-8', errors='ignore')
print(dump[:3000])

ssh.close()
