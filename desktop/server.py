"""Camera Lab: loopback-only dashboard and USB archival companion."""
from pathlib import Path
import argparse
import concurrent.futures
import json
import mimetypes
import shutil
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from archive import Archive
from companion import Adb, Companion
import metrics

STATIC = Path(__file__).resolve().parent / "static"

class App:
    def __init__(self, archive, companion, port):
        self.archive, self.companion, self.port = archive, companion, port
        self.analysis = {}
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.stopping = threading.Event()

    def state(self):
        result = self.companion.snapshot()
        result["analysisJobs"] = dict(self.analysis)
        usage = shutil.disk_usage(self.archive.root)
        result["diskFreeBytes"] = usage.free
        return result

class Handler(BaseHTTPRequestHandler):
    server_version = "CameraLab/1"
    protocol_version = "HTTP/1.1"

    @property
    def app(self):
        return self.server.app

    def log_message(self, format, *args):
        if args and str(args[0]).startswith("GET /api/events"):
            return
        print(self.log_date_time_string(), format % args, flush=True)

    def allowed(self, write=False):
        hosts = {"127.0.0.1:" + str(self.app.port), "localhost:" + str(self.app.port)}
        if self.headers.get("Host", "") not in hosts:
            return False
        origin = self.headers.get("Origin")
        if origin and origin not in {"http://" + host for host in hosts}:
            return False
        if write and self.headers.get("Sec-Fetch-Site") == "cross-site":
            return False
        return True

    def send_headers(self, status, kind, length=None, filename=None):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'")
        if length is not None:
            self.send_header("Content-Length", str(length))
        if filename:
            self.send_header("Content-Disposition", 'attachment; filename="' + filename + '"')
        self.end_headers()

    def data(self, data, kind="application/json; charset=utf-8", status=200, filename=None):
        if not isinstance(data, bytes):
            data = json.dumps(data, allow_nan=False).encode()
        self.send_headers(status, kind, len(data), filename)
        self.wfile.write(data)

    def file(self, path, kind=None, filename=None):
        self.send_headers(200, kind or mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                     path.stat().st_size, filename)
        with path.open("rb") as stream:
            shutil.copyfileobj(stream, self.wfile, length=65536)

    def error(self, error, status=400):
        self.data({"error": str(error)}, status=status)

    def do_GET(self):
        if not self.allowed():
            return self.error("Local access only", 403)
        route = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in route.path.split("/") if p]
        query = urllib.parse.parse_qs(route.query)
        try:
            if route.path == "/api/state":
                return self.data(self.app.state())
            if route.path == "/api/events":
                self.send_headers(200, "text/event-stream; charset=utf-8")
                previous = None
                try:
                    while not self.app.stopping.wait(.7):
                        payload = json.dumps(self.app.state(), allow_nan=False)
                        if payload != previous:
                            self.wfile.write(("event: state\ndata: " + payload + "\n\n").encode())
                            self.wfile.flush(); previous = payload
                        else:
                            self.wfile.write(b": heartbeat\n\n"); self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                return
            if route.path == "/api/sessions":
                return self.data(self.app.archive.list())
            if len(parts) >= 3 and parts[:2] == ["api", "sessions"]:
                session_id = parts[2]
                if len(parts) == 3:
                    return self.data(self.app.archive.get(session_id))
                operation = parts[3]
                if operation == "image" and len(parts) == 5:
                    return self.file(self.app.archive.image(session_id, parts[4]))
                if operation == "thumbnail" and len(parts) == 4:
                    directory = self.app.archive.directory(session_id)
                    path = directory / "analysis/thumb.jpg"
                    if not path.exists():
                        metrics.thumbnail(directory / "result.png", path)
                    return self.file(path, "image/jpeg")
                if operation == "difference" and len(parts) == 4:
                    a = self.app.archive.image(session_id, query.get("left", ["comparison-original.png"])[0])
                    b = self.app.archive.image(session_id, query.get("right", ["result.png"])[0])
                    return self.data(metrics.difference_png(a, b), "image/png")
                if operation == "metrics.csv" and len(parts) == 4:
                    return self.data(self.app.archive.csv(session_id), "text/csv; charset=utf-8", filename="camera-lab-metrics.csv")
                if operation == "export.zip" and len(parts) == 4:
                    bundle = self.app.archive.zip(session_id)
                    try:
                        return self.file(bundle, "application/zip", session_id + ".zip")
                    finally:
                        bundle.unlink(missing_ok=True)
            static_path = STATIC / (route.path.lstrip("/") or "index.html")
            if static_path.resolve().parent != STATIC.resolve() or not static_path.is_file():
                return self.error("Not found", 404)
            return self.file(static_path)
        except KeyError:
            self.error("Session not found", 404)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            self.error(error)

    def do_POST(self):
        if not self.allowed(write=True):
            return self.error("Local access only", 403)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.error("Invalid request size", 400)
        if not 0 < length <= 40 * 1024 * 1024:
            return self.error("Invalid request size", 413)
        route = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in route.path.split("/") if p]
        try:
            body = self.rfile.read(length)
            if route.path == "/api/settings":
                data = json.loads(body)
                if "paused" in data:
                    self.app.companion.paused = bool(data["paused"])
                if "serial" in data:
                    self.app.companion.choose_device(data["serial"])
                return self.data({"ok": True})
            if len(parts) == 4 and parts[:2] == ["api", "sessions"]:
                session_id, operation = parts[2], parts[3]
                self.app.archive.directory(session_id)
                if operation == "notes":
                    self.app.archive.set_notes(session_id, json.loads(body).get("notes", ""))
                    return self.data({"ok": True})
                if operation == "ground-truth":
                    if urllib.parse.parse_qs(route.query).get("registered") != ["true"]:
                        raise ValueError("Confirm ground-truth alignment first")
                    if self.app.analysis.get(session_id, {}).get("status") == "running":
                        raise ValueError("An analysis is already running")
                    self.app.analysis[session_id] = {"status": "running"}
                    def job():
                        try:
                            self.app.archive.analyse(session_id, body, True)
                            self.app.analysis[session_id] = {"status": "complete"}
                        except Exception as error:
                            self.app.analysis[session_id] = {"status": "error", "error": str(error)}
                    self.app.pool.submit(job)
                    return self.data({"status": "running"}, status=202)
            return self.error("Not found", 404)
        except KeyError:
            self.error("Session not found", 404)
        except Exception as error:
            self.error(error)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--archive", type=Path, default=Path(__file__).resolve().parents[1] / "desktop-data")
    parser.add_argument("--package", default="com.example.referencecamera.preview")
    parser.add_argument("--serial")
    args = parser.parse_args()
    if not args.adb or not Path(args.adb).is_file():
        raise SystemExit("Set --adb to the Android platform-tools adb.exe path.")
    archive = Archive(args.archive)
    companion = Companion(Adb(args.adb), archive, args.package, args.serial)
    app = App(archive, companion, args.port)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.app = app
    companion.start()
    print("Camera Lab ready at http://127.0.0.1:" + str(args.port), flush=True)
    print("Local archive: " + str(archive.root), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        app.stopping.set(); companion.stop_event.set(); server.server_close()
        app.pool.shutdown(wait=False, cancel_futures=True)

if __name__ == "__main__":
    main()