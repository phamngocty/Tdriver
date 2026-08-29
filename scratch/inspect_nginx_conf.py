import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /etc/nginx/nginx.conf')
print('=== NGINX.CONF ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm ls -la /etc/nginx/conf.d/')
print('=== CONF.D ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm ls -la /data/nginx/')
print('=== /DATA/NGINX ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
