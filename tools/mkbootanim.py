#!/usr/bin/env python3
"""Создаёт чёрную заставку без логотипов для совместимости со штатным bootanimation."""
import io
from pathlib import Path
import sys
import zipfile

from PIL import Image

OUT = sys.argv[1]
W, H = map(int, (sys.argv[2] if len(sys.argv) > 2 else "1920x1080").split("x"))

# Чёрный кадр служит только резервным вариантом, пока запускается живой экран журнала.
frame = Image.new("RGB", (W, H), "black")
buf = io.BytesIO()
frame.save(buf, "PNG", optimize=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED) as archive:
    archive.writestr("desc.txt", f"{W} {H} 1\np 0 0 part0\n")
    archive.writestr("part0/00000.png", buf.getvalue())
print(f"{OUT}: {W}x{H}, чёрный кадр без логотипа")
