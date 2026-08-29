import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

# Also copy into custom directory so it survives
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S cp /home/nas152/casaos_default.conf /home/nas152/tymap_data/npm/data/nginx/default_host/site.conf')
stdout.read()

ssh.close()
