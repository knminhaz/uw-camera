"""USB-only ADB companion. Only an explicit current phone request can archive images."""
from pathlib import Path
import hashlib
import json
import re
import socket
import subprocess
import threading
import time
import uuid
from archive import validate_manifest

PACKAGES = {"com.example.referencecamera", "com.example.referencecamera.preview"}

class Adb:
    def __init__(self, executable, port=5037):
        self.executable, self.port = str(executable), port

    def host(self, command):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as stream:
            data = command.encode()
            stream.sendall(("%04x" % len(data)).encode() + data)
            def read(count):
                result = b""
                while len(result) < count:
                    block = stream.recv(count - len(result))
                    if not block:
                        raise RuntimeError("ADB server closed the connection")
                    result += block
                return result
            status = read(4)
            size = int(read(4), 16)
            result = read(size).decode()
            if status != b"OKAY":
                raise RuntimeError(result)
            return result

    def devices(self):
        devices = []
        for line in self.host("host:devices-l").splitlines():
            parts = line.split()
            if len(parts) < 2:
                continue
            fields = {part.split(":", 1)[0]: part.split(":", 1)[1] for part in parts[2:] if ":" in part}
            devices.append(dict(serial=parts[0], state=parts[1], model=fields.get("model", "Android").replace("_", " ")))
        return devices

    def args(self, serial, *arguments):
        return [self.executable, "-P", str(self.port), "-s", serial, *arguments]

    def run(self, serial, *arguments, data=None, timeout=8):
        creation = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        result = subprocess.run(self.args(serial, *arguments), input=data, capture_output=True,
                                timeout=timeout, creationflags=creation)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace").strip() or "ADB command failed")
        return result.stdout

    def read_json(self, serial, package, filename):
        return json.loads(self.run(serial, "exec-out", "run-as", package, "cat", "files/bridge/" + filename))

    def write_json(self, serial, package, filename, payload):
        if filename not in {"heartbeat.json", "ack.json"}:
            raise ValueError("Unsupported bridge file")
        self.run(serial, "shell", "run-as", package, "mkdir", "-p", "files/bridge")
        command = "run-as " + package + " sh -c 'cat > files/bridge/" + filename + ".part'"
        self.run(serial, "shell", "-T", command, data=json.dumps(payload).encode())
        self.run(serial, "shell", "run-as", package, "mv",
                 "files/bridge/" + filename + ".part", "files/bridge/" + filename)

    def is_usb(self, device):
        serial = device["serial"]
        if not re.fullmatch(r"[A-Za-z0-9]{6,64}", serial):
            return False
        path = self.run(serial, "get-devpath").decode().strip()
        if path.startswith("usb:"):
            return True
        # Recent Windows ADB returns "unknown". A physical USB serial equals ro.serialno;
        # network and emulator transports have different serial formats.
        return self.run(serial, "shell", "getprop", "ro.serialno").decode().strip() == serial

    def archive_stream(self, serial, package, relative_directory, names):
        creation = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        return subprocess.Popen(self.args(serial, "exec-out", "run-as", package, "tar",
                                          "-C", relative_directory, "-cf", "-", *names),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=creation)

class Companion:
    def __init__(self, adb, archive, package, serial=None):
        if package not in PACKAGES:
            raise ValueError("Unsupported camera application")
        self.adb, self.archive, self.package = adb, archive, package
        self.target_serial = serial
        self.id = uuid.uuid4().hex
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.active = None
        self.processed = set()
        self.usb_serial = None
        self.paused = False
        self.phone_clock = None
        self.phone_instance = None
        self.phone_changed_at = 0.0
        self.phone_changes = 0
        self.events = []
        self.state = {"connection": "starting", "ready": False, "device": None, "devices": [],
                      "phone": None, "transfer": None, "message": "Looking for the USB companion…"}

    def event(self, label, kind="info"):
        with self.lock:
            self.events.append({"label": label, "kind": kind, "at": int(time.time()*1000)})
            self.events = self.events[-20:]

    def snapshot(self):
        with self.lock:
            return dict(self.state, companionId=self.id, paused=self.paused, events=list(self.events),
                        archivePath=str(self.archive.root), archiveRevision=self.archive.revision,
                        sessionCount=len(self.archive.list()), package=self.package)

    def choose_device(self, serial):
        with self.lock:
            if serial not in {d["serial"] for d in self.state["devices"]}:
                raise ValueError("Device is not connected")
            self.target_serial = serial
            self.usb_serial = None

    def start(self):
        self.thread = threading.Thread(target=self.loop, name="usb-companion", daemon=True)
        self.thread.start()

    def loop(self):
        while not self.stop_event.is_set():
            try:
                devices = self.adb.devices()
                if self.target_serial:
                    candidates = [d for d in devices if d["serial"] == self.target_serial]
                else:
                    candidates = devices if len(devices) == 1 else []
                with self.lock:
                    self.state["devices"] = devices
                if not candidates:
                    self.usb_serial = None
                    with self.lock:
                        self.state.update(connection="disconnected", ready=False, device=None, phone=None,
                                          message="Connect one phone by USB." if len(devices) < 2 else "Select a phone in Settings.")
                    self.stop_event.wait(1)
                    continue
                device = candidates[0]
                if device["state"] != "device":
                    with self.lock:
                        self.state.update(connection="unauthorized", ready=False, device=device, phone=None,
                                          message="Unlock the phone and allow USB debugging.")
                    self.stop_event.wait(1); continue
                if self.usb_serial != device["serial"]:
                    if not self.adb.is_usb(device):
                        with self.lock:
                            self.state.update(connection="not-usb", ready=False, device=device, phone=None,
                                              message="A wired USB connection is required.")
                        self.stop_event.wait(1); continue
                    self.usb_serial = device["serial"]
                try:
                    phone = self.adb.read_json(device["serial"], self.package, "status.json")
                    if phone.get("protocol") != 1 or not phone.get("instanceId"):
                        raise ValueError("Unsupported companion protocol")
                except Exception:
                    with self.lock:
                        self.state.update(connection="app-required", ready=False, device=device, phone=None,
                                          message="Open UW camera on the phone to connect.")
                    self.stop_event.wait(1); continue
                now = time.monotonic()
                if phone["instanceId"] != self.phone_instance:
                    self.phone_instance = phone["instanceId"]
                    self.phone_clock = phone["elapsedRealtimeMs"]
                    self.phone_changes = 0
                    self.phone_changed_at = now
                elif phone["elapsedRealtimeMs"] != self.phone_clock:
                    self.phone_clock = phone["elapsedRealtimeMs"]
                    self.phone_changed_at = now
                    self.phone_changes += 1
                fresh = self.phone_changes > 0 and now - self.phone_changed_at < 6.5
                ready = not self.paused and fresh
                heartbeat = {"protocol": 1, "companionId": self.id, "appInstanceId": phone["instanceId"],
                             "echoElapsedMs": phone["elapsedRealtimeMs"], "ready": ready,
                             "message": "Laptop archiving paused" if self.paused else "Laptop ready",
                             "archiveLabel": "Camera Lab laptop archive"}
                self.adb.write_json(device["serial"], self.package, "heartbeat.json", heartbeat)
                with self.lock:
                    self.state.update(connection="connected", ready=ready, device=device, phone=phone,
                                      message="Archiving paused." if self.paused else ("USB companion is ready." if fresh else "Waiting for a live phone heartbeat."))
                request = phone.get("transferRequest")
                if ready and isinstance(request, dict):
                    request_id = request.get("requestId")
                    if (request.get("companionId") == self.id and request.get("appInstanceId") == phone["instanceId"] and
                            isinstance(request_id, str) and request_id not in self.processed and not self.active):
                        self.active = request_id
                        threading.Thread(target=self.transfer, args=(device.copy(), request.copy()),
                                         name="verified-transfer", daemon=True).start()
            except (ConnectionError, OSError):
                self.usb_serial = None
                with self.lock:
                    self.state.update(connection="no-server", ready=False, device=None, phone=None, devices=[],
                                      message="Start the laptop launcher to enable USB access.")
            except Exception as error:
                with self.lock:
                    self.state.update(connection="error", ready=False, message=str(error)[:200])
            self.stop_event.wait(.8)

    def request_active(self, request):
        with self.lock:
            current = (self.state.get("phone") or {}).get("transferRequest") or {}
            return (not self.stop_event.is_set() and self.state.get("ready") and
                    current.get("requestId") == request.get("requestId") and
                    current.get("companionId") == self.id)

    def transfer(self, device, request):
        tar_path = self.archive.incoming / (uuid.uuid4().hex + ".tar.part")
        process = None
        try:
            text = request.get("manifestJson", "")
            if not isinstance(text, str) or len(text.encode()) > 65536:
                raise ValueError("Invalid transfer manifest")
            fingerprint = hashlib.sha256(text.encode()).hexdigest()
            if fingerprint != request.get("manifestSha256"):
                raise ValueError("Manifest checksum mismatch")
            manifest = json.loads(text)
            total = validate_manifest(manifest)
            directory = request.get("relativeDirectory")
            if directory not in {"cache/pending-captures/" + manifest["sessionId"], "files/captures/" + manifest["sessionId"]}:
                raise ValueError("Invalid phone session directory")
            self.event("Receiving approved capture from " + device["model"])
            with self.lock:
                self.state["transfer"] = {"phase": "receiving", "received": 0, "expected": total,
                                          "progress": 0, "sessionId": manifest["sessionId"]}
            process = self.adb.archive_stream(device["serial"], self.package, directory,
                                              [entry["name"] for entry in manifest["files"]])
            started = time.monotonic()
            finished = threading.Event()
            def guard():
                while not finished.wait(.3):
                    if not self.request_active(request) or time.monotonic() - started > 180:
                        process.terminate()
                        return
            monitor = threading.Thread(target=guard, daemon=True); monitor.start()
            received = 0
            try:
                with tar_path.open("wb") as output:
                    while True:
                        data = process.stdout.read(65536)
                        if not data:
                            break
                        received += len(data)
                        if received > total + 2 * 1024 * 1024:
                            raise ValueError("Transfer exceeded its declared size")
                        output.write(data)
                        with self.lock:
                            self.state["transfer"].update(received=received,
                                progress=min(97, received / max(total, 1) * 97))
                if process.wait(timeout=10):
                    raise RuntimeError(process.stderr.read().decode(errors="replace") or "USB transfer interrupted")
                if not self.request_active(request):
                    raise RuntimeError("Phone cancelled the transfer or the USB connection was lost")
                with self.lock:
                    self.state["transfer"].update(phase="verifying", progress=98)
                archive_id, duplicate = self.archive.commit(tar_path, manifest, device)
                ack = {"protocol": 1, "requestId": request["requestId"], "appInstanceId": request["appInstanceId"],
                       "companionId": self.id, "manifestSha256": fingerprint, "success": True,
                       "archiveId": archive_id, "fileCount": len(manifest["files"])}
                self.adb.write_json(device["serial"], self.package, "ack.json", ack)
                self.processed.add(request["requestId"])
                with self.lock:
                    self.state["transfer"].update(phase="complete", progress=100, archiveId=archive_id)
                self.event("Verified " + str(len(manifest["files"])) + " files · phone acknowledged", "success")
            finally:
                finished.set()
        except Exception as error:
            with self.lock:
                self.state["transfer"] = {"phase": "failed", "progress": 0, "error": str(error)[:250]}
            self.event("Transfer stopped: " + str(error)[:160], "error")
            try:
                self.adb.write_json(device["serial"], self.package, "ack.json",
                    {"protocol": 1, "requestId": request.get("requestId"), "appInstanceId": request.get("appInstanceId"),
                     "companionId": self.id, "manifestSha256": request.get("manifestSha256"), "success": False,
                     "error": str(error)[:250]})
                self.processed.add(request["requestId"])
            except Exception:
                pass
        finally:
            if process and process.poll() is None:
                process.terminate()
            tar_path.unlink(missing_ok=True)
            self.active = None