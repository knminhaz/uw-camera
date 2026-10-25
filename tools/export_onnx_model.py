"""Export and check the authors' Real-ESRGAN general x4v3 weights.

Source architecture/weights: https://github.com/xinntao/Real-ESRGAN
Copyright (c) 2021, Xintao Wang. BSD-3-Clause; see the bundled license.
This script uses the same SRVGGNetCompact topology and unchanged pretrained weights.
No training or photographic improvement is implied by these export checks.
"""
from pathlib import Path
import argparse
import hashlib
import json
import urllib.request
import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn
from torch.nn import functional as F

COMMIT = "a4abfb2979a7bbff3f69f58f58ae324608821e27"
WEIGHTS_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth"
WEIGHTS_SHA256 = "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292"
LICENSE_URL = "https://raw.githubusercontent.com/xinntao/Real-ESRGAN/" + COMMIT + "/LICENSE"
LICENSE_SHA256 = "4a699ec4863d96a91fc265948a0c90033f7e8735d515524dcf3444736406e0c2"
CORE, HALO = 96, 40

class GeneralPhotoModel(nn.Module):
    def __init__(self):
        super().__init__()
        layers = [nn.Conv2d(3, 64, 3, padding=1), nn.PReLU(64)]
        for _ in range(32):
            layers.extend([nn.Conv2d(64, 64, 3, padding=1), nn.PReLU(64)])
        layers.append(nn.Conv2d(64, 3 * 16, 3, padding=1))
        self.body = nn.ModuleList(layers)

    def forward(self, image):
        features = image
        for layer in self.body:
            features = layer(features)
        return F.pixel_shuffle(features, 4) + F.interpolate(image, scale_factor=4, mode="nearest")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def obtain(url, path, expected):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=120) as response:
            path.write_bytes(response.read())
    if digest(path) != expected:
        raise ValueError("Checksum mismatch: " + str(path))

def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])

def reduce_to_canvas(native):
    _, c, h4, w4 = native.shape
    h, w = h4 // 4, w4 // 4
    return np.clip(native, 0, 1).reshape(1, c, h, 4, w, 4).mean(axis=(3, 5))

def tiled_inference(runner, image, confidence):
    _, _, height, width = image.shape
    proposal = image.copy()
    count = 0
    for y in range(0, height, CORE):
        for x in range(0, width, CORE):
            w, h = min(CORE, width - x), min(CORE, height - y)
            if not np.any(confidence[..., y:y+h, x:x+w] > 0):
                continue
            left, top = max(0, x-HALO), max(0, y-HALO)
            right, bottom = min(width, x+w+HALO), min(height, y+h+HALO)
            patch = np.ascontiguousarray(image[..., top:bottom, left:right])
            native = runner.run(["upscaled"], {"image": patch})[0]
            low = reduce_to_canvas(native)
            proposal[..., y:y+h, x:x+w] = low[..., y-top:y-top+h, x-left:x-left+w]
            count += 1
    return proposal, count

def checks(model, runner):
    rng = np.random.default_rng(47)
    errors = []
    for h, w in [(17, 23), (37, 31), (65, 79)]:
        image = rng.random((1, 3, h, w), dtype=np.float32)
        with torch.inference_mode():
            expected = model(torch.from_numpy(image)).numpy()
        actual = runner.run(["upscaled"], {"image": image})[0]
        assert actual.shape == (1, 3, h*4, w*4)
        assert np.isfinite(actual).all()
        error = float(np.max(np.abs(expected-actual))); errors.append(error)
        assert error < 5e-5, error
    image = rng.random((1, 3, 121, 205), dtype=np.float32)
    all_confidence = np.ones((1, 1, 121, 205), dtype=np.float32)
    expected = reduce_to_canvas(runner.run(["upscaled"], {"image": image})[0])
    tiled, count = tiled_inference(runner, image, all_confidence)
    seam_error = float(np.max(np.abs(expected-tiled)))
    assert count > 1 and seam_error < 5e-5, seam_error
    empty = np.zeros_like(all_confidence)
    untouched, no_tiles = tiled_inference(runner, image, empty)
    assert no_tiles == 0 and np.array_equal(image, untouched)
    confidence = empty.copy(); confidence[..., 20:101, 45:161] = .7
    proposal, roi_tiles = tiled_inference(runner, image, confidence)
    baseline = np.rint(image * 255).astype(np.uint8)
    proposed = np.rint(np.clip(proposal, 0, 1) * 255).astype(np.uint8)
    final = np.rint(baseline.astype(np.float32) + confidence * (proposed.astype(np.float32) - baseline)).astype(np.uint8)
    outside = np.broadcast_to(confidence == 0, baseline.shape)
    assert np.array_equal(final[outside], baseline[outside])
    assert np.any(final != baseline)
    return {
        "pytorchOnnxMaxAbsError": max(errors), "tiledFullFrameMaxAbsError": seam_error,
        "tileCount": count, "roiTileCount": roi_tiles, "zeroConfidenceTilesRun": no_tiles,
        "outsideMaskPixelsPreserved": True, "hostOnly": True,
        "photographicQualityMeasured": False,
        "torch": torch.__version__, "onnx": onnx.__version__, "onnxruntime": ort.__version__,
    }

def test_fixtures(root):
    from onnx import helper as h, TensorProto as T
    folder = root / "app/src/androidTest/assets/models"; folder.mkdir(parents=True, exist_ok=True)
    inputs = [
        h.make_tensor_value_info("target", T.FLOAT, [1, 3, "h", "w"]),
        h.make_tensor_value_info("reference", T.FLOAT, [1, 3, "h", "w"]),
        h.make_tensor_value_info("confidence", T.FLOAT, [1, 1, "h", "w"]),
    ]
    output = h.make_tensor_value_info("enhanced", T.FLOAT, [1, 3, "h", "w"])
    constants = [h.make_tensor("strength", T.FLOAT, [1], [.1]),
                 h.make_tensor("minimum", T.FLOAT, [1], [0.]),
                 h.make_tensor("maximum", T.FLOAT, [1], [1.])]
    nodes = [h.make_node("Mul", ["reference", "confidence"], ["weighted"]),
             h.make_node("Mul", ["weighted", "strength"], ["detail"]),
             h.make_node("Add", ["target", "detail"], ["candidate"]),
             h.make_node("Clip", ["candidate", "minimum", "maximum"], ["enhanced"])]
    good = h.make_model(h.make_graph(nodes, "synthetic_reference_fixture", inputs, [output], constants),
                        opset_imports=[h.make_opsetid("", 17)], ir_version=9)
    onnx.checker.check_model(good); onnx.save(good, folder / "reference-fixture.onnx")
    bad_nodes = nodes[:-1] + [h.make_node("Div", ["candidate", "minimum"], ["enhanced"])]
    bad = h.make_model(h.make_graph(bad_nodes, "nonfinite_fixture", inputs, [output], constants),
                       opset_imports=[h.make_opsetid("", 17)], ir_version=9)
    onnx.checker.check_model(bad); onnx.save(bad, folder / "nonfinite-fixture.onnx")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    root, work = args.project.resolve(), args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    weights = work / "realesr-general-x4v3.pth"; obtain(WEIGHTS_URL, weights, WEIGHTS_SHA256)
    license_file = work / "LICENSE-Real-ESRGAN.txt"; obtain(LICENSE_URL, license_file, LICENSE_SHA256)
    torch.set_num_threads(2); torch.manual_seed(47)
    model = GeneralPhotoModel().eval()
    checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
    state = checkpoint["params_ema"] if "params_ema" in checkpoint else checkpoint["params"]
    model.load_state_dict(state, strict=True)
    assets = root / "app/src/main/assets/models"; assets.mkdir(parents=True, exist_ok=True)
    output = assets / "realesr-general-x4v3.onnx"
    torch.onnx.export(model, torch.zeros(1, 3, 48, 64), str(output), opset_version=17,
                      input_names=["image"], output_names=["upscaled"],
                      dynamic_axes={"image": {2: "height", 3: "width"}, "upscaled": {2: "height4", 3: "width4"}},
                      do_constant_folding=True, dynamo=False)
    graph = onnx.load(output)
    onnx.helper.set_model_props(graph, {"camera.model_id": "realesr-general-x4v3", "camera.scale": "4",
        "source.weights.sha256": WEIGHTS_SHA256, "source.repository": "https://github.com/xinntao/Real-ESRGAN",
        "source.commit": COMMIT, "license": "BSD-3-Clause"})
    onnx.checker.check_model(graph); onnx.save(graph, output)
    report = checks(model, session(output))
    manifest = {
        "id": "realesr-general-x4v3", "sha256": digest(output), "bytes": output.stat().st_size,
        "license": "BSD-3-Clause", "source": WEIGHTS_URL, "sourceWeightsSha256": WEIGHTS_SHA256,
        "upstreamCommit": COMMIT, "opset": 17, "nativeScale": 4, "savedCanvasScale": 1,
        "input": "image: float32 RGB [1,3,H,W], range [0,1]",
        "output": "upscaled: float32 RGB [1,3,4H,4W]",
        "role": "Single-image restoration of the OpenCV baseline; not a learned dual-camera fusion model",
        "tileCore": CORE, "tileHalo": HALO, "photographicQualityMeasured": False,
    }
    (assets / "model.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (assets / "LICENSE-Real-ESRGAN.txt").write_bytes(license_file.read_bytes())
    report["modelSha256"] = manifest["sha256"]
    validation = root / "docs/validation"; validation.mkdir(parents=True, exist_ok=True)
    (validation / "onnx-host-checks.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    test_fixtures(root)
    print(json.dumps({"model": manifest, "checks": report}, indent=2), flush=True)

if __name__ == "__main__":
    main()