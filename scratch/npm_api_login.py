import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

python_on_nas = """
import requests

# Try login to NPM
for pwd in ['changeme', '271000', 'admin', 'password', 'nas152']:
    try:
        r = requests.post('http://127.0.0.1:81/api/tokens', json={'identity': 'phamngocty2000@gmail.com', 'secret': pwd}, timeout=3)
        if r.status_code == 200:
            print('Logged in with secret:', pwd)
            token = r.json().get('token')
            # Create proxy host for 192.168.1.114
            headers = {'Authorization': f'Bearer {token}'}
            ph_resp = requests.post('http://127.0.0.1:81/api/nginx/proxy-hosts', headers=headers, json={
                'domain_names': ['192.168.1.114'],
                'forward_scheme': 'http',
                'forward_host': '192.168.1.114',
                'forward_port': 8090,
                'access_list_id': 0,
                'certificate_id': 0,
                'ssl_forced': False,
                'caching_enabled': False,
                'block_exploits': False,
                'advanced_config': '',
                'meta': {},
                'allow_websocket_upgrade': True,
                'http2_support': False,
                'hsts_enabled': False,
                'hsts_subdomains': False
            })
            print('Create proxy host response:', ph_resp.status_code, ph_resp.text)
            break
    except Exception as e:
        print('Error:', e)
"""

cmd = f"echo 271000 | sudo -S python3 -c \"{python_on_nas}\""
stdin, stdout, stderr = ssh.exec_command(cmd)
print('Output:')
print(stdout.read().decode('utf-8', errors='ignore'))
print(stderr.read().decode('utf-8', errors='ignore'))

ssh.close()
