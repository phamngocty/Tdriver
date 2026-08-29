import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const internalNginx = require('/app/internal/nginx.js');
const internalSetting = require('/app/internal/setting.js');
async function run() {
    try {
        const res = await internalSetting.set({
            id: 'default-site',
            name: 'Default Site',
            description: 'What to show when Nginx is hit with an unknown Host',
            value: 'redirect',
            meta: {
                redirect: 'http://192.168.1.114:8090'
            }
        });
        console.log('Setting updated:', res);
        await internalNginx.renderDefault();
        await internalNginx.reload();
        console.log('Rendered and reloaded successfully!');
    } catch (e) {
        console.error('Error:', e);
    }
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
print('Execution result:')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

ssh.close()
