import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const knex = require('knex')({client:'sqlite3', connection:{filename:'/data/database.sqlite'}});
async function run() {
    const auth = await knex('auth').select('*');
    console.log('Auth records:', auth);
    const ph = await knex('proxy_host').select('*');
    console.log('Proxy hosts:', ph);
    await knex.destroy();
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
res = stdout.read().decode('utf-8', errors='ignore')
print(res)

ssh.close()
