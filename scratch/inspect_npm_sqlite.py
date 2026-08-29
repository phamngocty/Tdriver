import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S sqlite3 /home/nas152/tymap_data/npm/data/database.sqlite "SELECT id, domain_names, forward_host, forward_port FROM proxy_host;"')
print('Proxy hosts in SQLite:')
print(stdout.read().decode('utf-8', errors='ignore'))

stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S sqlite3 /home/nas152/tymap_data/npm/data/database.sqlite "SELECT * FROM setting WHERE id=\'default-site\';"')
print('Setting default-site:')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
