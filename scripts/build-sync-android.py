#!/usr/bin/env python3
"""Build VerveTwoDo's gomobile adapter against its pinned sync-core dependency."""
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
module = root / 'sync-engine'
output = root / 'app/build/generated/sync/vervesync.aar'
tools = root / 'build/sync-tools'
output.parent.mkdir(parents=True, exist_ok=True)
tools.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env['GOWORK'] = 'off'
env['GOPRIVATE'] = ','.join(filter(None, [env.get('GOPRIVATE', ''), 'github.com/CamilleHbp/sync-core']))
if not env.get('ANDROID_HOME'):
    env['ANDROID_HOME'] = str(Path.home() / 'Library/Android/sdk')
if not env.get('ANDROID_NDK_HOME'):
    env['ANDROID_NDK_HOME'] = str(Path(env['ANDROID_HOME']) / 'ndk/29.0.14206865')
if not env.get('JAVA_HOME'):
    env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
subprocess.run(['go', 'mod', 'verify'], cwd=module, env=env, check=True)
subprocess.run(['go', 'build', '-o', str(tools / 'gobind'), 'golang.org/x/mobile/cmd/gobind'], cwd=module, env=env, check=True)
env['PATH'] = str(tools) + os.pathsep + env['PATH']
temporary = output.with_name('vervesync-building.aar')
subprocess.run(['go', 'run', 'golang.org/x/mobile/cmd/gomobile', 'bind', '-target=android/arm64,android/amd64', '-androidapi=26', '-trimpath', '-ldflags=-s -w', '-o', str(temporary), './mobile'], cwd=module, env=env, check=True)
with zipfile.ZipFile(temporary) as archive:
    if archive.testzip() is not None:
        raise RuntimeError('Invalid generated Android archive')
temporary.replace(output)
print(output)
