import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /etc/nginx/conf.d/include/assets.conf')
print('assets.conf:')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /etc/nginx/nginx/html/index.html')
print('/etc/nginx/nginx/html/index.html:')
print(stdout.read().decode('utf-8', errors='ignore')[:300])

ssh.close()
