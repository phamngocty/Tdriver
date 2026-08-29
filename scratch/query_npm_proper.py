import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

node_script = """
const knex = require('knex')({client:'sqlite3', connection:{filename:'/data/database.sqlite'}});
async function run() {
    const users = await knex('user').select('id', 'email', 'name');
    console.log('Users:', users);
    const settings = await knex('setting').select('*');
    console.log('Settings:', settings);
    await knex.destroy();
}
run();
"""

cmd = f"echo 271000 | sudo -S docker exec -i tymap_npm node -e \"{node_script}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
print('NPM data:')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

ssh.close()
