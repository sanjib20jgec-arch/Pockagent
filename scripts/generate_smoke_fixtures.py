#!/usr/bin/env python3
"""Generate text-only fixtures for device smoke tests.

The generated assets are intentionally not committed. CI runs this script before
`connectedDebugAndroidTest`; `.gitignore` keeps the output out of the source archive.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "androidTest" / "assets" / "smoke"


def minimal_pdf() -> bytes:
    content = b"BT /F1 18 Tf 72 720 Td (PocketAgent offline smoke PDF) Tj ET\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(content)).encode("ascii") + b" >>\nstream\n" + content + b"endstream",
    ]

    pdf = bytearray(b"%PDF-1.4\n")
    offsets: list[int] = []
    for index, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{index} 0 obj\n".encode("ascii"))
        pdf.extend(obj)
        pdf.extend(b"\nendobj\n")

    xref_offset = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    pdf.extend(b"0000000000 65535 f \n")
    for offset in offsets:
        pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    pdf.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode("ascii")
    )
    return bytes(pdf)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    files = {
        "smoke.pdf": minimal_pdf(),
        "smoke.txt": b"PocketAgent offline attachment fixture.\nContains no network dependencies.\n",
        "placeholder_whisper.bin": b"NOT_A_VALID_GGML_MODEL\n",
    }
    for name, payload in files.items():
        (OUTPUT / name).write_bytes(payload)

    manifest = {
        name: {
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        for name, payload in files.items()
    }
    (OUTPUT / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"Generated {len(files)} smoke fixtures in {OUTPUT}")


if __name__ == "__main__":
    main()
