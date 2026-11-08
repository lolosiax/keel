"""从 keel-icon-hollow.svg / keel-icon-solid.svg 重出 Windows 默认应用图标。

规则：>= 40px 用空心骨架版，< 40px 用实心版（小尺寸下细线会糊）。
输出：keel-classpath-gradle-plugin 的 resources，随插件 jar 一起分发给所有消费方。

依赖 Pillow；改了两个 SVG 之后在本目录执行 `python generate-icon.py`。
"""

from pathlib import Path

from PIL import Image, ImageDraw
import io
import struct

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "keel-classpath-gradle-plugin" / "src" / "main" / "resources" / "moe" / "lolosia" / "keel" / "gradle" / "classpath" / "keel-icon.ico"

SIZES = [16, 24, 32, 48, 64, 128, 256]
SOLID_BELOW = 40
SS = 4  # 超采样倍数

GRADIENT = [(0.0, (0x7F, 0x52, 0xFF)), (0.5, (0xB1, 0x25, 0xEA)), (1.0, (0xE4, 0x48, 0x57))]

# viewBox 100x100 内的 K 轮廓（原始 32x32 坐标经 translate(18,18) scale(2)）
PATH_A = [(2, 2), (2, 30), (30, 30), (29.953, 30), (23.003, 23), (16.1, 15.946), (23.05, 8.934), (29.938, 2)]
PATH_B = [(16.318, 2), (2, 16.318), (2, 30), (2.124, 30), (16.132, 15.992), (16.101, 15.961), (23.05, 8.95), (29.938, 2)]
TRI_TOP_LEFT = [(2, 2), (16.318, 2), (2, 16.318)]
TRI_BOTTOM = [(2, 30), (30, 30), (16.1, 15.946)]


def to_px(points, k):
    return [(18 * k + 2 * k * x, 18 * k + 2 * k * y) for x, y in points]


def gradient_tile(size):
    k = size / 100.0
    stops = [(t, c) for t, c in GRADIENT]
    ramp = bytearray(3 * (2 * size))
    for s in range(2 * size):
        t = (((s + 1) / k) - 8) / 184.0
        t = min(1.0, max(0.0, t))
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]
            t1, c1 = stops[i + 1]
            if t <= t1 or i == len(stops) - 2:
                f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
                f = min(1.0, max(0.0, f))
                rgb = tuple(round(c0[j] + (c1[j] - c0[j]) * f) for j in range(3))
                break
        ramp[3 * s:3 * s + 3] = bytes(rgb)
    rows = [bytes(ramp[3 * y:3 * y + 3 * size]) for y in range(size)]
    return Image.frombytes("RGB", (size, size), b"".join(rows))


def white_layer(size, solid, band_alpha):
    layer = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    k = size / 100.0
    if solid:
        translucent = Image.new("L", (size, size), 0)
        ImageDraw.Draw(translucent).polygon(to_px(PATH_B, k), fill=band_alpha)
        layer.paste((255, 255, 255, 255), (0, 0), translucent)

        opaque = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(opaque)
        draw.polygon(to_px(TRI_TOP_LEFT, k), fill=255)
        draw.polygon(to_px(TRI_BOTTOM, k), fill=255)
        layer.paste((255, 255, 255, 255), (0, 0), opaque)
    else:
        outer = Image.new("L", (size, size), 0)
        ImageDraw.Draw(outer).line(to_px(PATH_A, k) + [to_px(PATH_A[:1], k)[0]],
                                   fill=255, width=max(1, round(6.1 * k)), joint="curve")
        layer.paste((255, 255, 255, 255), (0, 0), outer)

        inner = Image.new("L", (size, size), 0)
        ImageDraw.Draw(inner).line(to_px(PATH_B, k) + [to_px(PATH_B[:1], k)[0]],
                                   fill=191, width=max(1, round(3.32 * k)), joint="curve")
        layer.paste((255, 255, 255, 255), (0, 0), inner)
    return layer


def render(size):
    big = size * SS
    tile = gradient_tile(big).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [4 * big / 100, 4 * big / 100, 96 * big / 100, 96 * big / 100],
        radius=20 * big / 100, fill=255,
    )
    tile.putalpha(mask)
    # 24px 及以下半透明带只剩几个像素，会糊；小尺寸直接全白保证轮廓
    small = size <= 24
    tile.alpha_composite(white_layer(big, solid=size < SOLID_BELOW, band_alpha=255 if small else 128))
    return tile.resize((size, size), Image.LANCZOS)


def main():
    frames = []
    for size in SIZES:
        buf = io.BytesIO()
        render(size).save(buf, format="PNG", optimize=True)
        frames.append((size, buf.getvalue()))

    header = struct.pack("<HHH", 0, 1, len(frames))
    offset = 6 + 16 * len(frames)
    entries, blobs = b"", b""
    for size, data in frames:
        entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
        blobs += data
        offset += len(data)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(header + entries + blobs)
    print(f"{OUT}  ({len(header + entries + blobs)} bytes, {len(frames)} sizes)")


if __name__ == "__main__":
    main()
