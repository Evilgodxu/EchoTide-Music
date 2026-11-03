"""死区标定：对比「有损转码」与「数字带限」两种链路的高频死区性质。

仅在 12kHz 以上定位砖墙，避免低频打击乐下降沿干扰；逐文件输出高频逐点电平，
使死区形态可直接横向对比。
"""

from __future__ import annotations

import io
import sys

import numpy as np
import soundfile as sf
from numpy.fft import rfft, irfft

FFT = 4096
HOP = FFT // 2


def spectrum(data, sr):
    mono = data.mean(axis=1)
    w = np.hanning(FFT)
    acc = np.zeros(FFT // 2 + 1)
    cnt = 0
    for i in range(0, len(mono) - FFT + 1, HOP):
        acc += np.abs(np.fft.rfft(mono[i:i + FFT] * w)) ** 2
        cnt += 1
    ps = acc / cnt
    db = 10 * np.log10(ps + 1e-14)
    return db - db.max(), sr / FFT


def report(name, data, sr):
    dbp, bh = spectrum(data, sr)
    full = float(np.abs(data).max())
    off = 20 * np.log10(full + 1e-12)          # dBFS 偏移（满幅 0dBFS=1.0）
    n = len(dbp) - 1
    lo = int(12000 / bh)
    grad = np.diff(dbp[lo:])
    k = lo + int(np.argmin(grad))
    cliff = k * bh
    d0 = min(int((cliff * 1.03) / bh), n - 8)
    dead = dbp[d0:] + off
    marks = " ".join(f"{f/1000:.1f}k:{dbp[int(f/bh)]+off:>6.1f}" for f in (14000, 16000, 17000, 18000, 18400, 18800, 20000, 22000))
    print(f"{name:<24} 墙≈{cliff:>6.0f}Hz | 死区中位={np.median(dead):>7.1f}dBFS "
          f"p95={np.percentile(dead,95):>7.1f}dBFS 平坦={np.percentile(dead,95)-np.percentile(dead,5):>5.1f}dB")
    print(f"{'':<24} {marks}")


def main(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    print(f"source {sr}Hz 2ch {len(data)/sr:.1f}s   16bit LSB = {20*np.log10(1/32768):.1f}dBFS\n")
    report("原始文件", data, sr)

    for br in (320, 192, 128):
        buf = io.BytesIO()
        sf.write(buf, data, sr, format="MP3", subtype="MPEG_LAYER_III",
                 compression_level=(1.0 - br / 320) * 0.9 + 0.05)
        size = len(buf.getvalue())
        buf.seek(0)
        d2, sr2 = sf.read(buf, dtype="float32", always_2d=True)
        if len(d2) != len(data):
            print(f"MP3 {br}k 长度异常 {len(d2)} vs {len(data)} (bytes={size})")
            continue
        q = np.round(np.clip(d2, -1, 1) * 32767) / 32767
        report(f"MP3 {br}kbps→16bit({size//1024}KB)", q, sr2)

    out = np.empty_like(data)
    for ch in range(data.shape[1]):
        S = rfft(data[:, ch].astype(np.float64))
        S[int(18500 / (sr / len(data))):] = 0
        out[:, ch] = irfft(S, len(data))
    q = np.round(np.clip(out, -1, 1) * 32767) / 32767
    report("数字低通18.5k→16bit", q, sr)


if __name__ == "__main__":
    main(sys.argv[1])
