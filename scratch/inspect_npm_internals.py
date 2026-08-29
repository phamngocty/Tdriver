import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const internalNginx = require('/app/internal/nginx.js');
const internalSetting = require('/app/internal/setting.js');
async function run() {
    console.log('internalSetting:', Object.keys(internalSetting));
    console.log('internalNginx:', Object.keys(internalNginx));
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
print('NPM internals:')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
