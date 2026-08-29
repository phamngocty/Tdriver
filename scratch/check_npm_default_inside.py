import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S ls -la /home/nas152/tymap_data/npm/data/nginx/default_host 2>/dev/null')
print('=== DEFAULT_HOST FILES ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /etc/nginx/conf.d/default.conf')
print('=== NGINX CONF.D DEFAULT.CONF ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
