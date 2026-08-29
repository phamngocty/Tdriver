import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker inspect tymap_npm | grep -A 10 Mounts')
print('=== NPM MOUNTS ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S ls -la /home/nas152/tymap_data/npm 2>/dev/null || echo 271000 | sudo -S docker volume ls')
print('=== NPM DATA DIR ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
