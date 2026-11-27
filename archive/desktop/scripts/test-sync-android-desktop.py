#!/usr/bin/env python3
"""Exercise the actual gomobile adapter and desktop helper against a disposable service."""
import argparse
import json
import os
from pathlib import Path
import socket
import re
import shutil
import subprocess
import tempfile
import time
import uuid
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--private-config', type=Path, help='Synthetic tests only: loopback private development network config')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb')
server_binary = root / 'desktopIntegration/build/sync-test/server'
helper_binary = root / 'desktopApp/build/generated/resources/common/vervetwodo-helper'
with tempfile.TemporaryDirectory(prefix='verve-device-sync-') as tmp:
    directory = Path(tmp)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    server = subprocess.Popen([server_binary], env=dict(os.environ, DATA_DIR=str(directory/'server'), LISTEN_ADDR=f'127.0.0.1:{port}'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    helper = subprocess.Popen([helper_binary, '-data-dir', directory/'desktop'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    test = None
    def request(method, payload=None):
        helper.stdin.write(json.dumps(dict(version=1, id=str(uuid.uuid4()), method='engine.'+method, payload=payload or {}))+'\n'); helper.stdin.flush()
        reply = json.loads(helper.stdout.readline())
        while reply.get('event'):
            reply = json.loads(helper.stdout.readline())
        assert 'error' not in reply, reply
        return reply['sync']
    try:
        deadline = time.monotonic()+15
        while True:
            try:
                with urllib.request.urlopen(origin+'/healthz', timeout=1): break
            except OSError:
                if time.monotonic() > deadline: raise
                time.sleep(.1)
        if args.private_config:
            server.terminate(); server.wait()
            network_id = re.search(r'^networkId:\s*(\S+)\s*$', args.private_config.read_text(), re.M).group(1)
            env = dict(os.environ, DATA_DIR=str(directory/'server'), SYNC_NETWORK_CONFIG=str(args.private_config.resolve()), SYNC_NETWORK_ID=network_id, SYNC_NATIVE_DIRECTORY=str(directory/'migration'))
            subprocess.run([server_binary, 'prepare-native'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=90)
            shutil.copyfile(directory/'migration/provision.json', directory/'server/native-provision.json')
            server = subprocess.Popen([server_binary], env=dict(os.environ, DATA_DIR=str(directory/'server'), LISTEN_ADDR=f'127.0.0.1:{port}'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            deadline = time.monotonic()+15
            while True:
                try:
                    with urllib.request.urlopen(origin+'/healthz', timeout=1): break
                except OSError:
                    if time.monotonic()>deadline: raise
                    time.sleep(.1)
            for native_port in range(19001,19007):
                subprocess.run([adb,'-s',args.serial,'reverse',f'tcp:{native_port}',f'tcp:{native_port}'],check=True,stdout=subprocess.DEVNULL)
        code = json.loads((directory/'server/access-code.json').read_text())
        assert not request('connect', dict(origin=origin, code=code)).get('error')
        subprocess.run([adb,'-s',args.serial,'reverse',f'tcp:{port}',f'tcp:{port}'],check=True)
        record_id = str(uuid.uuid4())
        test = subprocess.Popen([adb,'-s',args.serial,'shell','am','instrument','-w','-r','-e','class','cn.super12138.todo.NativeNetworkSyncTest',
            '-e','native','true' if args.private_config else 'false','-e','syncOrigin',origin,'-e','syncCode',code,'-e','syncRecord',record_id,'studio.camille.vervetwodo.test/androidx.test.runner.AndroidJUnitRunner'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        changed = False
        completed = False
        deadline = time.monotonic()+45
        while time.monotonic() < deadline:
            result = request('state' if args.private_config else 'sync')
            records = result['state']['records']
            record = next((r for r in records if r['id']==record_id),None)
            if record and not changed:
                reply = request('apply', dict(operations=[dict(id=str(uuid.uuid4()),record=record_id,kind='task',field='details',value='From desktop',replaces=[])]))
                assert not reply.get('error'), reply
                changed=True
            if record and any(v['value']=='true' for v in record['fields'].get('completed',[])):
                if args.private_config:
                    versions=[v['id'] for v in record['fields'].get('details',[])]
                    request('apply', dict(operations=[dict(id=str(uuid.uuid4()),record=record_id,kind='task',field='details',value='Desktop observed completion',replaces=versions)]))
                completed=True; break
            time.sleep(.1)
        output,_ = test.communicate(timeout=15)
        print(output)
        assert completed and 'OK (1 test)' in output, 'Cross-platform synchronization failed'
        print('PASS: Android creation → desktop edit → Android completion → desktop projection')
    finally:
        if test and test.poll() is None: test.terminate(); test.wait()
        helper.terminate(); helper.wait()
        server.terminate(); server.wait()
        subprocess.run([adb,'-s',args.serial,'reverse','--remove',f'tcp:{port}'],check=False,stdout=subprocess.DEVNULL)
        if args.private_config:
            for native_port in range(19001,19007):
                subprocess.run([adb,'-s',args.serial,'reverse','--remove',f'tcp:{native_port}'],check=False,stdout=subprocess.DEVNULL)
