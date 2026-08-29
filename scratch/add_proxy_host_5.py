import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const knex = require('knex')({client:'sqlite3', connection:{filename:'/data/database.sqlite'}});
const internalNginx = require('/app/internal/nginx.js');

async function run() {
    try {
        const existing = await knex('proxy_host').where('id', 5).first();
        const hostObj = {
            id: 5,
            owner_user_id: 1,
            is_deleted: 0,
            domain_names: JSON.stringify(["192.168.1.114"]),
            forward_host: "172.17.0.1",
            forward_port: 8090,
            access_list_id: 0,
            certificate_id: 0,
            ssl_forced: 0,
            caching_enabled: 0,
            block_exploits: 0,
            advanced_config: "",
            meta: JSON.stringify({nginx_online: true, nginx_err: null}),
            allow_websocket_upgrade: 1,
            http2_support: 0,
            forward_scheme: "http",
            enabled: 1,
            locations: "[]",
            hsts_enabled: 0,
            hsts_subdomains: 0,
            trust_forwarded_proto: 0
        };
        if (!existing) {
            await knex('proxy_host').insert(hostObj);
        } else {
            await knex('proxy_host').where('id', 5).update(hostObj);
        }
        
        // Pass host model format to renderProxyHost
        const hostData = {
            ...hostObj,
            domain_names: ["192.168.1.114"],
            locations: []
        };
        await internalNginx.renderProxyHost(hostData);
        await internalNginx.reload();
        console.log('SUCCESS: Proxy host 5 for 192.168.1.114 created and reloaded!');
    } catch(e) {
        console.error('ERROR:', e);
    } finally {
        await knex.destroy();
    }
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
res = stdout.read().decode('utf-8', errors='ignore')
print(res)

ssh.close()
