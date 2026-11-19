#!/usr/bin/env python3
"""Generates crisp PNG & SVG app icons for NexusTS ChromeOS PWA & Isolated Web App."""

import math
import os
import struct
import zlib

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "icons")
os.makedirs(OUT_DIR, exist_ok=True)

FAVICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" fill="none">
  <defs>
    <linearGradient id="bg" x1="20" y1="16" x2="236" y2="240" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#0D1E38"/>
      <stop offset="55%" stop-color="#0A1526"/>
      <stop offset="100%" stop-color="#07101D"/>
    </linearGradient>
    <linearGradient id="strokeGrad" x1="48" y1="48" x2="208" y2="208" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#5AC8FA"/>
      <stop offset="100%" stop-color="#229ED9"/>
    </linearGradient>
  </defs>
  <rect x="8" y="8" width="240" height="240" rx="54" fill="url(#bg)" stroke="#2A4A73" stroke-width="4"/>
  <path d="M68 176V80L132 146V80" stroke="url(#strokeGrad)" stroke-width="20" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M150 112L188 144L150 176" stroke="#4FE3C1" stroke-width="18" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="188" cy="86" r="12" fill="#4FE3C1"/>
</svg>
"""


def write_png(path: str, width: int, height: int, rgba_bytes: bytes) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    raw_rows = bytearray()
    stride = width * 4
    for y in range(height):
        raw_rows.append(0)  # filter type 0
        raw_rows.extend(rgba_bytes[y * stride : (y + 1) * stride])

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw_rows), level=9)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def dist_to_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    c1 = vx * wx + vy * wy
    if c1 <= 0:
        return math.hypot(px - ax, py - ay)
    c2 = vx * vx + vy * vy
    if c2 <= c1:
        return math.hypot(px - bx, py - by)
    t = c1 / c2
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


def render_icon(size: int, maskable: bool = False) -> bytes:
    buf = bytearray(size * size * 4)
    scale = size / 256.0
    pad = 0.0 if maskable else 10.0 * scale
    radius = 0.0 if maskable else 54.0 * scale

    for y in range(size):
        for x in range(size):
            px = x + 0.5
            py = y + 0.5

            # Rounded-rect alpha check
            alpha = 1.0
            if not maskable:
                left, top = pad, pad
                right, bottom = size - pad, size - pad
                if px < left or px > right or py < top or py > bottom:
                    alpha = 0.0
                else:
                    cx = min(max(px, left + radius), right - radius)
                    cy = min(max(py, top + radius), bottom - radius)
                    d = math.hypot(px - cx, py - cy)
                    if d > radius + 0.8:
                        alpha = 0.0
                    elif d > radius - 0.8:
                        alpha = (radius + 0.8 - d) / 1.6

            if alpha <= 0.0:
                continue

            u = px / size
            v = py / size
            # Deep slate-navy gradient background
            r = int(13 * (1 - v) + 7 * v)
            g = int(30 * (1 - v) + 16 * v)
            b = int(56 * (1 - v) + 29 * v)

            nx = px / scale
            ny = py / scale
            # 'N' monogram strokes in cyan-blue
            d_n = min(
                dist_to_segment(nx, ny, 70, 174, 70, 82),
                dist_to_segment(nx, ny, 70, 82, 132, 146),
                dist_to_segment(nx, ny, 132, 146, 132, 82),
            )
            if d_n < 10.5:
                cov = min(1.0, max(0.0, (10.5 - d_n) / 1.5))
                cr = int(90 * (1 - u) + 34 * u)
                cg = int(200 * (1 - u) + 158 * u)
                cb = int(250 * (1 - u) + 217 * u)
                r = int(r * (1 - cov) + cr * cov)
                g = int(g * (1 - cov) + cg * cov)
                b = int(b * (1 - cov) + cb * cov)

            # Terminal chevron '>' & connected node dot in mint-teal
            d_chev = min(
                dist_to_segment(nx, ny, 150, 114, 186, 144),
                dist_to_segment(nx, ny, 186, 144, 150, 174),
                max(0.0, math.hypot(nx - 186, ny - 86) - 3.0),
            )
            if d_chev < 9.2:
                cov = min(1.0, max(0.0, (9.2 - d_chev) / 1.5))
                r = int(r * (1 - cov) + 79 * cov)
                g = int(g * (1 - cov) + 227 * cov)
                b = int(b * (1 - cov) + 193 * cov)

            idx = (y * size + x) * 4
            buf[idx] = max(0, min(255, r))
            buf[idx + 1] = max(0, min(255, g))
            buf[idx + 2] = max(0, min(255, b))
            buf[idx + 3] = int(alpha * 255)

    return bytes(buf)


def main() -> None:
    with open(os.path.join(OUT_DIR, "favicon.svg"), "w", encoding="utf-8") as f:
        f.write(FAVICON_SVG)
    write_png(os.path.join(OUT_DIR, "icon-192.png"), 192, 192, render_icon(192, maskable=False))
    write_png(os.path.join(OUT_DIR, "icon-512.png"), 512, 512, render_icon(512, maskable=False))
    write_png(os.path.join(OUT_DIR, "icon-maskable-512.png"), 512, 512, render_icon(512, maskable=True))
    print("Generated favicon.svg, icon-192.png, icon-512.png, icon-maskable-512.png")


if __name__ == "__main__":
    main()
