import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const internalProxyHost = require('/app/internal/proxy-host.js');
async function run() {
    try {
        const host = await internalProxyHost.create({
            domain_names: ['192.168.1.114'],
            forward_scheme: 'http',
            forward_host: '192.168.1.114',
            forward_port: 8090,
            access_list_id: 0,
            certificate_id: 0,
            ssl_forced: 0,
            caching_enabled: 0,
            block_exploits: 1,
            advanced_config: '',
            meta: {},
            allow_websocket_upgrade: 1,
            http2_support: 0,
            hsts_enabled: 0,
            hsts_subdomains: 0
        });
        console.log('Created proxy host ID:', host.id);
    } catch(e) {
        console.error('Create error:', e.message || e);
    }
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
res = stdout.read().decode('utf-8', errors='ignore')
for line in res.splitlines():
    try:
        print(line)
    except Exception:
        pass

ssh.close()
