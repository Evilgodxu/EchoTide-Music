"""FLAC 元数据探针：枚举全部 metadata block，重点导出 Vorbis Comment 与 APPLICATION 载荷。

用途：核对音频容器/编码链是否残留生成器写入的标签，作为 AI 合成判定的一级证据。
仅依赖标准库，避免为一次性取证引入三方依赖。
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

BLOCK_NAMES = {
    0: "STREAMINFO",
    1: "PADDING",
    2: "APPLICATION",
    3: "SEEKTABLE",
    4: "VORBIS_COMMENT",
    5: "CUESHEET",
    6: "PICTURE",
}

# 常见 AI 音乐生成链路的出品标识；命中即视为「生成器自报」的强证据
GENERATOR_HINTS = (
    "suno", "udio", "riffusion", "mubert", "boomy", "aiva", "soundraw",
    "beatoven", "loudly", "stability", "stable audio", "musicgen", "audiocraft",
    "ace-step", "acestep", "diffrhythm", "yue", "mureka", "海绵音乐", "天工",
    "ai music", "generated", "diffusion", "comfyui", "sovits", "rvc",
)


def read_blocks(data: bytes):
    if data[:4] != b"fLaC":
        raise SystemExit("not a FLAC stream (missing fLaC magic)")
    offset = 4
    index = 0
    while True:
        header = data[offset]
        last = bool(header & 0x80)
        btype = header & 0x7F
        size = struct.unpack(">I", b"\x00" + data[offset + 1:offset + 4])[0]
        body = data[offset + 4:offset + 4 + size]
        yield index, btype, last, body
        offset += 4 + size
        index += 1
        if last:
            break
    print(f"[audio payload starts at byte {offset}, total {len(data)}]")


def parse_streaminfo(body: bytes):
    if len(body) < 34:
        return
    min_bs, max_bs = struct.unpack(">HH", body[0:4])
    min_fs = int.from_bytes(body[4:7], "big")
    max_fs = int.from_bytes(body[7:10], "big")
    # 采样率/声道/位深打包在 8 字节中
    packed = int.from_bytes(body[10:18], "big")
    sample_rate = (packed >> 44) & 0xFFFFF
    channels = ((packed >> 41) & 0x7) + 1
    bits = ((packed >> 36) & 0x1F) + 1
    total_samples = packed & 0xFFFFFFFFF
    print(f"  STREAMINFO: {sample_rate}Hz {channels}ch {bits}bit samples={total_samples} "
          f"duration={total_samples / sample_rate:.2f}s minBS={min_bs} maxBS={max_bs} "
          f"minFS={min_fs} maxFS={max_fs}")


def parse_vorbis(body: bytes):
    off = 0
    vlen = struct.unpack("<I", body[off:off + 4])[0]
    off += 4
    vendor = body[off:off + vlen].decode("utf-8", "replace")
    off += vlen
    count = struct.unpack("<I", body[off:off + 4])[0]
    off += 4
    print(f"  VORBIS vendor = {vendor!r}  ({count} comment(s))")
    comments = []
    for _ in range(count):
        clen = struct.unpack("<I", body[off:off + 4])[0]
        off += 4
        raw = body[off:off + clen].decode("utf-8", "replace")
        off += clen
        comments.append(raw)
    for c in comments:
        print(f"    {c}")
    blob = (vendor + "\n" + "\n".join(comments)).lower()
    hits = sorted({h for h in GENERATOR_HINTS if h in blob})
    if hits:
        print(f"  !! GENERATOR HINTS HIT: {hits}")
    return vendor, comments


def parse_application(body: bytes):
    app_id = body[:4]
    print(f"  APPLICATION id = {app_id!r}")
    payload = body[4:]
    print(f"    payload({len(payload)}B) = {payload[:120]!r}")
    for hint in (b"suno", b"udio", b"musicgen", b"ace", b"diff"):
        if hint in body.lower():
            print(f"    !! payload mentions {hint!r}")


def parse_picture(body: bytes):
    off = 0

    def u32():
        nonlocal off
        v = struct.unpack(">I", body[off:off + 4])[0]
        off += 4
        return v

    ptype, mlen = u32(), u32()
    mime = body[off:off + mlen].decode("utf-8", "replace"); off += mlen
    dlen = u32(); desc = body[off:off + dlen].decode("utf-8", "replace"); off += dlen
    w, h, depth, colors, dsize = u32(), u32(), u32(), u32(), u32()
    print(f"  PICTURE type={ptype} mime={mime} desc={desc!r} {w}x{h} {depth}bit {dsize}B ({dsize/1048576:.2f}MB)")


def main(path: str):
    data = Path(path).read_bytes()
    print(f"file = {path}  ({len(data)} bytes)")
    if data[:3] == b"ID3":
        print("!! leading ID3v2 tag detected (non-canonical for FLAC)")
    for index, btype, last, body in read_blocks(data):
        name = BLOCK_NAMES.get(btype, f"UNKNOWN({btype})")
        print(f"[block {index}] {name} size={len(body)} last={last}")
        if btype == 0:
            parse_streaminfo(body)
        elif btype == 4:
            parse_vorbis(body)
        elif btype == 2:
            parse_application(body)
        elif btype == 6:
            parse_picture(body)


if __name__ == "__main__":
    main(sys.argv[1])
