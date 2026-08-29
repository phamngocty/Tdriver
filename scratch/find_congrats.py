import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm find / -name "index.html" 2>/dev/null')
print('NPM index.html locations:')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm grep -r "Congratulations" /app /var /etc /usr 2>/dev/null')
print('Grep Congratulations in NPM:')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
