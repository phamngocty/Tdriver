import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S head -n 50 /home/nas152/tymap_data/npm/data/nginx/proxy_host/*.conf')
print('=== PROXY HOSTS ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
