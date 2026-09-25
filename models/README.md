# Local segmentation models

Model binaries are downloaded locally and are intentionally excluded from Git.

The production engine uses `modnet_photographic.onnx`. It is an ONNX-simplified
MODNet model with a fixed `input` shape of `1×3×1024×576`, which is compatible
with OpenCV 4.10. Docker verifies the expected SHA-256 during image creation so
the production image cannot silently use an older model.

`u2net_human_seg.onnx` remains only as a local fallback for development.
