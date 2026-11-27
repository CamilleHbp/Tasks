#!/usr/bin/env python3
"""Deploy the separate personal VerveTwoDo service to the configured homeserver."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
stage = root / 'build/sync-server'
stage.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, GOWORK='off', GOPRIVATE='github.com/CamilleHbp/sync-core', CGO_ENABLED='0', GOOS='linux', GOARCH='amd64')
subprocess.run(['go', 'build', '-trimpath', '-o', str(stage / 'vervetwodo-server'), './cmd/server'], cwd=root / 'sync-engine', env=env, check=True)
remote = '/srv/homeserver/builds/vervetwodo'
subprocess.run(['ssh', 'nf-showcases', 'mkdir', '-p', remote], check=True)
subprocess.run(['scp', str(stage / 'vervetwodo-server'), str(root / 'sync-engine/Dockerfile'), str(root / 'sync-engine/compose.yaml'), f'nf-showcases:{remote}/'], check=True)
script = r'''
from pathlib import Path
import datetime, shutil, subprocess
root = Path('/home/debian/HomeServer')
stage = Path('/srv/homeserver/builds/vervetwodo')
data = Path('/srv/homeserver/data/vervetwodo')
data.mkdir(mode=0o700, exist_ok=True)
def run(*args): subprocess.run(args, cwd=root, check=True)
run('docker', 'compose', '-f', str(stage/'compose.yaml'), '-p', 'vervetwodo', 'up', '-d', '--build', '--wait')
caddy = root/'Caddyfile'
content = caddy.read_text()
domain = 'vervetwodo.195-15-214-30.sslip.io'
if domain not in content:
    candidate = stage/'Caddyfile.candidate'
    candidate.write_text(content + '\n' + domain + ' {\n    reverse_proxy vervetwodo:8080\n}\n')
    run('docker', 'run', '--rm', '-v', str(candidate)+':/etc/caddy/Caddyfile:ro', 'caddy:2.10.2-alpine', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile')
    backup = caddy.with_name('Caddyfile.before-vervetwodo-'+datetime.datetime.now().strftime('%Y%m%d%H%M%S'))
    shutil.copy2(caddy, backup)
    try:
        caddy.write_text(candidate.read_text())
        run('docker', 'compose', 'restart', 'caddy')
    except BaseException:
        caddy.write_text(backup.read_text())
        run('docker', 'compose', 'restart', 'caddy')
        raise
print('VerveTwoDo service deployed with its separate data directory.')
'''
subprocess.run(['ssh', 'nf-showcases', 'python3', '-'], input=script, text=True, check=True)
