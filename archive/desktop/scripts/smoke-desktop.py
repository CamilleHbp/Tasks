#!/usr/bin/env python3
"""Test packaged-app command processing with disposable, isolated task data."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def wait_for(check, description):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(0.1)
    raise RuntimeError("Timed out: " + description)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("app", type=Path, help="packaged .app directory on macOS or application directory on Windows")
    args = parser.parse_args()
    app = args.app.resolve()
    mac = app.suffix == ".app"
    executable = app / ("Contents/MacOS/VerveTwoDo" if mac else "VerveTwoDo.exe")
    helper = app / ("Contents/app/resources/vervetwodo-helper" if mac else "app/resources/vervetwodo-helper.exe")
    launcher = app / ("Contents/app/VerveTwoDo.cfg" if mac else "app/VerveTwoDo.cfg")
    assert "-Dvervetwodo.helpers=" not in launcher.read_text(encoding="utf-8"), "Development paths must not be embedded in the packaged launcher"
    with tempfile.TemporaryDirectory(prefix="vervetwodo-smoke-") as temporary:
        directory = Path(temporary)
        task_id = str(uuid.uuid4())
        seed = {"version": 1, "id": "seed", "method": "command", "command": {
            "version": 1, "id": task_id, "action": "add", "title": "Préparer le test"}}
        seeded = subprocess.run([str(helper), "-data-dir", temporary], input=json.dumps(seed) + "\n",
                                capture_output=True, text=True, encoding="utf-8", check=True)
        assert not json.loads(seeded.stdout).get("error"), seeded.stdout
        environment = dict(os.environ, VERVETWODO_PREVIEW_DATA=temporary)
        with (directory / "app.log").open("w") as log:
            process = subprocess.Popen([str(executable)], env=environment, stdout=log, stderr=log)
            try:
                snapshot_file = directory / "snapshot.json"
                snapshot = lambda: json.loads(snapshot_file.read_text(encoding="utf-8"))
                wait_for(lambda: snapshot()["tasks"][0]["id"] == task_id, "initial task projection")
                widget_directory = directory / "widget" if mac else directory
                widget_snapshot = lambda: json.loads((widget_directory / "snapshot.json").read_text(encoding="utf-8"))
                wait_for(lambda: widget_snapshot()["tasks"][0]["id"] == task_id, "separate widget projection")
                inbox = widget_directory / "commands"
                inbox.mkdir(exist_ok=True)

                def enqueue(command):
                    temporary_file = inbox / (command["id"] + ".tmp")
                    temporary_file.write_text(json.dumps(command), encoding="utf-8")
                    temporary_file.replace(inbox / (command["id"] + ".json"))

                stale = {"version": 1, "id": "00000000-0000-4000-8000-000000000000", "action": "complete",
                         "taskId": str(uuid.uuid4()), "completed": True}
                valid = {"version": 1, "id": "11111111-1111-4111-8111-111111111111", "action": "complete",
                         "taskId": task_id, "completed": True}
                enqueue(stale)
                enqueue(valid)
                accepted = inbox / (valid["id"] + ".json")
                wait_for(lambda: snapshot()["tasks"][0]["completed"] and not accepted.exists(), "completion after a stale command")
                wait_for(lambda: widget_snapshot()["tasks"][0]["completed"], "widget completion projection")
                assert (inbox / (stale["id"] + ".json.rejected")).exists()
                revision = snapshot()["revision"]
                enqueue(valid)
                wait_for(lambda: not accepted.exists(), "durable command replay")
                assert snapshot()["revision"] == revision, "A replay repeated a mutation"
                subprocess.run([str(executable), "vervetwodo-preview://task?id=" + task_id], env=environment,
                               stdout=log, stderr=log, timeout=15, check=True)
                wait_for(lambda: not list((directory / "commands").glob("*.json")), "activation routed to resident process")
                assert process.poll() is None, "The resident application exited unexpectedly"
                log.flush()
                if mac:
                    assert "Notification initialization failed:" not in (directory / "app.log").read_text(encoding="utf-8"), "The native foreground notification delegate could not be installed"
                print("PASS: bundled helper, startup projection, stale-command isolation, completion, replay, resident activation")
            except Exception:
                log.flush()
                print((directory / "app.log").read_text(encoding="utf-8", errors="replace"))
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
