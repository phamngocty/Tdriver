import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S ls -la /home/nas152/tymap_data/npm/data/nginx/')
print('=== NPM NGINX DIR ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S cat /home/nas152/tymap_data/npm/data/nginx/default_host/site.conf 2>/dev/null || echo 271000 | sudo -S cat /home/nas152/tymap_data/npm/data/nginx/default.conf 2>/dev/null')
print('=== DEFAULT HOST CONF ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
