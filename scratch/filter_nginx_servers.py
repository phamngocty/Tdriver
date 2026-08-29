import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm nginx -T')
dump = stdout.read().decode('utf-8', errors='ignore')

# Print only lines containing server, listen, server_name, root, proxy_pass, location
for line in dump.splitlines():
    if any(k in line for k in ['# configuration file', 'server {', 'listen ', 'server_name ', 'root ', 'proxy_pass ']):
        print(line)

ssh.close()
