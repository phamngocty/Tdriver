import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker ps --format "table {{.Names}}\t{{.Ports}}"')
print('=== DOCKER CONTAINERS ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S ss -tulpn | grep LISTEN')
print('=== LISTENING PORTS ===')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('cat /etc/casaos/gateway.ini 2>/dev/null')
print('=== CASAOS GATEWAY INI ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
