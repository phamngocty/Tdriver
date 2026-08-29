import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.114', username='nas152', password='271000', timeout=5)

redirect_html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta http-equiv="refresh" content="0; url=http://192.168.1.114:8090/">
    <script>
        var target = 'http://' + window.location.hostname + ':8090/';
        window.location.replace(target);
    </script>
    <title>CasaOS</title>
</head>
<body style="background:#1a1b1e;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
    <div style="text-align:center;">
        <h2>Đang chuyển hướng tới CasaOS...</h2>
        <p><a href="http://192.168.1.114:8090/" style="color:#00aaff;">Bấm vào đây nếu không tự chuyển</a></p>
    </div>
</body>
</html>
"""

# Write index.html using root inside container
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec -u 0 -i tymap_npm sh -c "cat > /var/www/html/index.html"')
stdin.write(redirect_html)
stdin.flush()
stdin.close()
print('Write index.html stdout:', stdout.read().decode('utf-8', errors='ignore'))
print('Write index.html stderr:', stderr.read().decode('utf-8', errors='ignore'))

# Verify content of /var/www/html/index.html
stdin, stdout, stderr = ssh.exec_command('echo 271000 | sudo -S docker exec tymap_npm cat /var/www/html/index.html')
print('=== NEW /var/www/html/index.html ===')
print(stdout.read().decode('utf-8', errors='ignore'))

# Also test curl http://192.168.1.114/
stdin, stdout, stderr = ssh.exec_command('curl -s http://127.0.0.1:80/')
print('=== CURL RESULT ===')
print(stdout.read().decode('utf-8', errors='ignore'))

ssh.close()
