#!/usr/bin/env python3
"""Exercise the widget helper against an isolated real sync server and web gateway."""
from pathlib import Path
import http.cookiejar
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[2]


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def wait(condition, what, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = condition()
            if value:
                return value
        except (OSError, ValueError, KeyError, StopIteration):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + what)


with tempfile.TemporaryDirectory(prefix='vervetwodo-widget-sync-') as temp:
    directory = Path(temp)
    processes, logs = [], []
    env = dict(os.environ, GOWORK='off')
    subprocess.run(['go', 'build', '-o', str(directory / 'sync'), './cmd/server'], cwd=root / 'sync-engine', env=env, check=True)
    subprocess.run(['go', 'build', '-o', str(directory / 'web'), '.'], cwd=root / 'pwa/server', env=env, check=True)
    subprocess.run(['go', 'build', '-o', str(directory / 'helper'), '.'], cwd=root / 'native/widget-helper', env=env, check=True)
    try:
        def launch(binary, data, url, extra={}):
            log = open(directory / (data + '.log'), 'w')
            logs.append(log)
            processes.append(subprocess.Popen([str(directory / binary)], stdout=log, stderr=log,
                                              env=dict(env, DATA_DIR=str(directory / data), LISTEN_ADDR=url.removeprefix('http://'), **extra)))
            wait(lambda: urllib.request.urlopen(url + '/healthz', timeout=1).read() is not None, binary + ' start')

        upstream = f'http://127.0.0.1:{port()}'
        (directory / 'upstream').mkdir()
        launch('sync', 'upstream', upstream)
        code = json.loads((directory / 'upstream/access-code.json').read_text())
        origin = f'http://127.0.0.1:{port()}'
        launch('web', 'web-data', origin, {'SYNC_ORIGIN': upstream, 'PUBLIC_ORIGIN': origin, 'ASSET_DIR': str(root / 'pwa/dist')})
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def web(method, body={}):
            request = urllib.request.Request(origin + '/api/' + method, data=json.dumps(body).encode(),
                                             headers={'Origin': origin, 'Content-Type': 'application/json'})
            with opener.open(request, timeout=70) as r:
                return json.load(r)

        web('login', {'code': code})
        task = str(uuid.uuid4())
        fields = {'title': 'Water plants', 'completed': 'false', 'dueDate': '2027-01-31', 'createdAt': '7', 'tags': '[]',
                  'recurrence': '{"frequency":"monthly","interval":1,"anchor":"2027-01-31"}', 'deleted': 'false'}
        web('apply', {'operations': [{'id': str(uuid.uuid4()), 'record': task, 'kind': 'task', 'field': f, 'value': v, 'replaces': []}
                                     for f, v in fields.items()]})
        web('check')

        widgets = directory / 'widgets'
        helper_log = open(directory / 'helper.log', 'w')
        logs.append(helper_log)
        def start_helper():
            process = subprocess.Popen([str(directory / 'helper'), '--data-dir', str(directory / 'helper-data'), '--widget-dir', str(widgets)],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=helper_log, text=True)
            processes.append(process)
            return process

        helper = start_helper()

        def request(method, **fields):
            helper.stdin.write(json.dumps({'id': method, 'method': method, **fields}) + '\n')
            helper.stdin.flush()
            while True:
                line = json.loads(helper.stdout.readline())
                if line.get('id') == method:
                    return line

        reply = request('connect', origin=upstream, code=code)
        assert not reply.get('error') and reply['status']['authenticated'], reply
        snapshot = widgets / 'snapshot.json'
        loaded = wait(lambda: next(t for t in json.loads(snapshot.read_text())['tasks'] if t['id'] == task), 'task in widget snapshot')
        assert loaded['title'] == 'Water plants' and not loaded['completed'], loaded
        assert 'code' not in snapshot.read_text()

        (widgets / 'commands').mkdir(exist_ok=True)
        command = {'version': 1, 'id': str(uuid.uuid4()), 'action': 'complete', 'taskId': task, 'title': '', 'completed': True}
        (widgets / 'commands' / (command['id'] + '.json')).write_text(json.dumps(command))
        wait(lambda: not any((widgets / 'commands').iterdir()), 'command drained')

        def remote():
            web('check')
            return {r['id']: r for r in web('state')['state']['records']}

        def completed_with_successor():
            records = remote()
            done = records[task]['fields']['completed'][0]['value'] == 'true'
            successor = next((r for r in records.values() if r['fields'].get('parentId', [{}])[0].get('value') == task), None)
            return done and successor
        successor = wait(completed_with_successor, 'completion and recurring successor reach the workspace')
        assert successor['fields']['dueDate'][0]['value'] == '2027-02-28', successor
        assert successor['fields']['completed'][0]['value'] == 'false'
        wait(lambda: any(t['id'] == successor['id'] for t in json.loads(snapshot.read_text())['tasks']), 'successor in widget snapshot')

        # Unticking in the widget reopens the task and retracts the untouched successor.
        command = {'version': 1, 'id': str(uuid.uuid4()), 'action': 'complete', 'taskId': task, 'title': '', 'completed': False}
        (widgets / 'commands' / (command['id'] + '.json')).write_text(json.dumps(command))
        wait(lambda: not any((widgets / 'commands').iterdir()), 'reopen command drained')

        def reopened_and_retracted():
            records = remote()
            fields = records[successor['id']]['fields']
            return (records[task]['fields']['completed'][0]['value'] == 'false'
                    and fields['deleted'][0]['value'] == 'true' and fields['retracted'][0]['value'] == 'true')
        wait(reopened_and_retracted, 'reopened task and retracted successor reach the workspace')
        wait(lambda: not next(t for t in json.loads(snapshot.read_text())['tasks'] if t['id'] == task)['completed']
             and all(t['id'] != successor['id'] for t in json.loads(snapshot.read_text())['tasks']), 'reopen in widget snapshot')

        assert not request('locale', locale='fr').get('error')
        assert json.loads(snapshot.read_text())['locale'] == 'fr'
        workspace = request('status')['status']['workspace']
        helper.stdin.close()
        assert helper.wait(timeout=30) == 0
        helper = start_helper()
        restored = request('status')['status']
        assert restored['authenticated'] and restored['workspace'] == workspace
        assert not request('sync').get('error')
        wait(lambda: request('status')['status']['confirmed'], 'confirmed sync after restart without a code')
        expected_snapshot = json.loads(snapshot.read_text())
        for damage in ('missing', 'corrupt'):
            if damage == 'missing':
                snapshot.unlink()
            else:
                snapshot.write_text('{')
            assert not request('sync').get('error')
            wait(lambda: json.loads(snapshot.read_text()) == expected_snapshot, 'repair ' + damage + ' widget snapshot', timeout=8)
        assert not request('disconnect').get('error') and not snapshot.exists()
        helper.stdin.close()
        helper.wait(timeout=30)
        print('PASS: connect, projection, widget completion with recurring successor, reopen with retraction, locale, saved pairing after restart, disconnect.')
    except BaseException:
        for log in logs:
            log.flush()
        for path in directory.glob('*.log'):
            print(path.name, path.read_text()[-3000:])
        raise
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()
