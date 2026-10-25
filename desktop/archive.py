"""Verified local session archive. Phone-provided paths never become filesystem paths."""
from pathlib import Path
import csv
from contextlib import contextmanager
import hashlib
import io
import json
import os
import re
import shutil
import sqlite3
import tarfile
import threading
import time
import uuid
import zipfile
from PIL import Image, ImageOps
import metrics

STAGES = {
    "ultrawide.jpg": ("Captured ultrawide", "capture"),
    "main.jpg": ("Captured main", "capture"),
    "comparison-original.png": ("Original ultrawide", "original"),
    "prepared-ultrawide.png": ("Prepared ultrawide", "prepare"),
    "prepared-main.png": ("Prepared main", "prepare"),
    "aligned-reference.png": ("Aligned reference", "align"),
    "aligned-reference.jpg": ("Aligned reference", "align"),
    "confidence.png": ("Reference confidence", "confidence"),
    "baseline.png": ("OpenCV baseline", "fusion"),
    "onnx-proposal.png": ("Model proposal", "ai"),
    "onnx-result.png": ("AI blended result", "ai"),
    "result.png": ("Final image", "final"),
}
SOURCE_ID = re.compile(r"^[0-9]{10,16}-[a-z0-9]{6,32}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
MAX_TOTAL = 2 * 1024 * 1024 * 1024
MANIFEST_NAME = "transfer-manifest.json"

def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def validate_manifest(manifest):
    if manifest.get("protocol") != 1 or not SOURCE_ID.fullmatch(str(manifest.get("sessionId", ""))):
        raise ValueError("Invalid transfer session")
    files = manifest.get("files")
    if not isinstance(files, list) or not 1 <= len(files) <= 128:
        raise ValueError("Invalid file manifest")
    seen = set(); total = 0
    for entry in files:
        name = entry.get("name", "")
        if not SAFE_NAME.fullmatch(name) or ".." in name or name == MANIFEST_NAME or name in seen:
            raise ValueError("Unsafe or duplicate transfer filename")
        if not isinstance(entry.get("bytes"), int) or not 0 <= entry["bytes"] <= MAX_TOTAL:
            raise ValueError("Invalid file size")
        if not re.fullmatch(r"[0-9a-f]{64}", str(entry.get("sha256", ""))):
            raise ValueError("Invalid file checksum")
        seen.add(name); total += entry["bytes"]
    if total > MAX_TOTAL:
        raise ValueError("Session exceeds the transfer limit")
    for required in ("result.png", "baseline.png", "comparison-original.png", "processing.json", "capture.json"):
        if required not in seen:
            raise ValueError("Session is missing " + required)
    return total

def manifest_digest(manifest):
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

class Archive:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.sessions_root = self.root / "sessions"
        self.incoming = self.root / ".incoming"
        self.sessions_root.mkdir(parents=True, exist_ok=True)
        self.incoming.mkdir(exist_ok=True)
        self.db_path = self.root / "index.sqlite3"
        self.lock = threading.RLock()
        self.revision = 0
        with self.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY, source_id TEXT NOT NULL, manifest_hash TEXT NOT NULL,
                captured_at INTEGER NOT NULL, archived_at INTEGER NOT NULL,
                summary TEXT NOT NULL, quality TEXT, notes TEXT NOT NULL DEFAULT ''
            )""")

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.db_path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def directory(self, archive_id):
        with self.db() as db:
            if not db.execute("SELECT id FROM sessions WHERE id=?", (archive_id,)).fetchone():
                raise KeyError("Session not found")
        path = (self.sessions_root / archive_id).resolve()
        if path.parent != self.sessions_root:
            raise ValueError("Invalid session path")
        return path

    def list(self):
        with self.db() as db:
            rows = db.execute("SELECT * FROM sessions ORDER BY archived_at DESC LIMIT 250").fetchall()
        return [dict(json.loads(row["summary"]), id=row["id"], notes=row["notes"],
                     quality=json.loads(row["quality"]) if row["quality"] else None) for row in rows]

    def get(self, archive_id):
        for item in self.list():
            if item["id"] == archive_id:
                return item
        raise KeyError("Session not found")

    def commit(self, tar_path, manifest, device):
        total = validate_manifest(manifest)
        fingerprint = manifest_digest(manifest)
        archive_id = manifest["sessionId"] + "-" + fingerprint[:8]
        with self.lock:
            with self.db() as db:
                existing = db.execute("SELECT id FROM sessions WHERE id=?", (archive_id,)).fetchone()
            if existing:
                # Never acknowledge a duplicate whose existing archive has been damaged.
                existing_dir = self.directory(archive_id)
                for entry in manifest["files"]:
                    path = existing_dir / entry["name"]
                    if not path.is_file() or path.stat().st_size != entry["bytes"] or digest(path) != entry["sha256"]:
                        raise ValueError("Existing archive failed checksum verification")
                return archive_id, True
            if shutil.disk_usage(self.root).free < total + 64 * 1024 * 1024:
                raise ValueError("Insufficient free archive space")
            staging = self.incoming / ("verify-" + uuid.uuid4().hex)
            staging.mkdir()
            expected = {entry["name"]: entry for entry in manifest["files"]}
            seen = set()
            try:
                with tarfile.open(tar_path, "r:*") as stream:
                    for entry in stream:
                        name = entry.name.removeprefix("./")
                        if name in ("", ".") and entry.isdir():
                            continue
                        if (not entry.isfile() or not SAFE_NAME.fullmatch(name) or ".." in name or
                                name not in expected or name in seen):
                            raise ValueError("Unexpected or unsafe archive member: " + name)
                        if entry.size != expected[name]["bytes"]:
                            raise ValueError("Transfer size mismatch: " + name)
                        incoming = stream.extractfile(entry)
                        if incoming is None:
                            raise ValueError("Unreadable archive member")
                        path = staging / name
                        with path.open("wb") as output:
                            shutil.copyfileobj(incoming, output, length=65536)
                            output.flush(); os.fsync(output.fileno())
                        if digest(path) != expected[name]["sha256"]:
                            raise ValueError("Checksum mismatch: " + name)
                        seen.add(name)
                if seen != set(expected):
                    raise ValueError("Transfer is incomplete")
                (staging / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2), encoding="utf-8")
                processing = json.loads((staging / "processing.json").read_text(encoding="utf-8"))
                capture = json.loads((staging / "capture.json").read_text(encoding="utf-8"))
                stages = []
                for name, (label, step) in STAGES.items():
                    if name not in expected:
                        continue
                    path = staging / name
                    with Image.open(path) as image:
                        if image.width * image.height > metrics.MAX_PIXELS:
                            raise ValueError("Image is too large to inspect")
                        oriented = ImageOps.exif_transpose(image)
                        width, height = oriented.size
                    stages.append({"file": name, "label": label, "step": step,
                                   "width": width, "height": height, "bytes": expected[name]["bytes"]})
                prepared = staging / "prepared-ultrawide.png"
                reference_path = prepared if prepared.is_file() else staging / "comparison-original.png"
                baseline_pixels = metrics.rgb(reference_path)
                changes = {}
                for stage in stages:
                    if stage["file"] != "confidence.png" and (stage["height"], stage["width"]) == baseline_pixels.shape[:2]:
                        changes[stage["file"]] = metrics.change(baseline_pixels, metrics.rgb(staging / stage["file"]))
                now = int(time.time()*1000)
                summary = {"changes": changes,
                    "sourceId": manifest["sessionId"], "capturedAt": int(manifest["sessionId"].split("-")[0]),
                    "archivedAt": now, "device": device.get("model", "Android phone"),
                    "files": stages, "fileCount": len(expected), "bytes": total, "verified": True,
                    "aiApplied": bool(processing.get("aiApplied")),
                    "referenceUsed": bool(processing.get("alignmentAccepted")),
                    "model": processing.get("enhancement", {}).get("modelId"),
                    "captureMode": capture.get("captureMode", "unknown"),
                    "synthetic": bool(capture.get("syntheticFixture", False)),
                    "captureMs": processing.get("captureDurationMs"),
                    "processingMs": processing.get("processingDurationMs"),
                    "exposureGapMs": processing.get("timestampDeltaMs"),
                    "enhancementMs": processing.get("enhancement", {}).get("durationMs"),
                    "stageTimings": processing.get("stageTimingsMs", {}),
                    "fusion": processing.get("fusionMetrics", {}),
                    "processingNotes": processing.get("notes", ""),
                }
                destination = self.sessions_root / archive_id
                if destination.exists():
                    raise ValueError("Archive directory already exists without an index record")
                staging.rename(destination)
                try:
                    with self.db() as db:
                        db.execute("INSERT INTO sessions(id,source_id,manifest_hash,captured_at,archived_at,summary) VALUES(?,?,?,?,?,?)",
                                   (archive_id, manifest["sessionId"], fingerprint, summary["capturedAt"], now, json.dumps(summary)))
                except BaseException:
                    destination.rename(staging)
                    raise
                self.revision += 1
                return archive_id, False
            finally:
                if staging.exists():
                    shutil.rmtree(staging)

    def image(self, archive_id, name):
        info = self.get(archive_id)
        if name not in {entry["file"] for entry in info["files"]}:
            raise ValueError("Not an archived image stage")
        return self.directory(archive_id) / name

    def set_notes(self, archive_id, text):
        self.directory(archive_id)
        if not isinstance(text, str) or len(text) > 4000:
            raise ValueError("Notes must be at most 4000 characters")
        with self.lock, self.db() as db:
            db.execute("UPDATE sessions SET notes=? WHERE id=?", (text, archive_id))
            self.revision += 1

    def analyse(self, archive_id, payload, registered):
        if registered is not True:
            raise ValueError("Confirm that ground truth is already aligned to the output")
        directory = self.directory(archive_id)
        analysis = directory / "analysis"; analysis.mkdir(exist_ok=True)
        with Image.open(io.BytesIO(payload)) as image:
            if image.width * image.height > metrics.MAX_PIXELS:
                raise ValueError("Ground truth exceeds the analysis limit")
            reference_image = ImageOps.exif_transpose(image).convert("RGB")
            with Image.open(directory / "result.png") as final:
                if reference_image.size != final.size:
                    raise ValueError("Ground truth must match final-image dimensions exactly; it is never auto-resized")
            temporary = analysis / "reference.part.png"
            reference_image.save(temporary, "PNG")
        reference = metrics.rgb(temporary)
        baseline = metrics.rgb(directory / "comparison-original.png")
        rows = []
        for stage in self.get(archive_id)["files"]:
            if stage["file"] == "confidence.png":
                continue
            candidate = metrics.rgb(directory / stage["file"])
            if candidate.shape != reference.shape:
                continue
            rows.append(dict(stage=stage["file"], label=stage["label"], **metrics.quality(reference, candidate),
                             change=metrics.change(baseline, candidate)))
        reference_path = analysis / "ground-truth.png"; temporary.replace(reference_path)
        result = {"status": "complete", "registeredByUser": True, "referenceSha256": digest(reference_path),
                  "createdAt": int(time.time()*1000), "method": "RGB PSNR; Gaussian SSIM 11x11 sigma 1.5, population covariance",
                  "rows": rows}
        (analysis / "quality.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        with self.lock, self.db() as db:
            db.execute("UPDATE sessions SET quality=? WHERE id=?", (json.dumps(result), archive_id))
            self.revision += 1
        return result

    def csv(self, archive_id):
        info = self.get(archive_id); buffer = io.StringIO()
        writer = csv.writer(buffer)
        writer.writerow(["stage", "width", "height", "bytes", "psnr_db", "ssim", "ground_truth_sha256"])
        quality = info.get("quality") or {}
        rows = {r["stage"]: r for r in quality.get("rows", [])}
        for stage in info["files"]:
            measurement = rows.get(stage["file"], {})
            writer.writerow([stage["label"], stage["width"], stage["height"], stage["bytes"],
                             "inf" if measurement.get("perfectMatch") else measurement.get("psnrDb", ""),
                             measurement.get("ssim", ""), quality.get("referenceSha256", "")])
        return buffer.getvalue().encode("utf-8")

    def zip(self, archive_id):
        directory = self.directory(archive_id)
        target = self.incoming / (archive_id + "-" + uuid.uuid4().hex + ".zip")
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as bundle:
            for file in sorted(directory.rglob("*")):
                if file.is_file() and file.suffix not in (".part",):
                    bundle.write(file, arcname=file.relative_to(directory))
            bundle.writestr("metrics.csv", self.csv(archive_id))
        return target