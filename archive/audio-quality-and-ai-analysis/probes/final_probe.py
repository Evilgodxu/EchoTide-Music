"""最终一轮特征取证：立体声来源、时序规律、全曲谱。

目标是在无负样本的前提下，判断哪些量具备「AI 专属」的判别力，
从而只把有把握的判据写进 Kotlin，避免引入误报。
"""

from __future__ import annotations

import sys

import numpy as np
import soundfile as sf

FFT = 4096
HOP = FFT // 2


def band_corr(x, y, sr, lo, hi):
    """分带 L/R 相关系数：单声道骨干扩宽的伪立体声在各带都接近 1。"""
    w = np.hanning(FFT)
    bh = sr / FFT
    a, b = int(lo / bh), int(hi / bh)
    num = den1 = den2 = 0.0
    for i in range(0, len(x) - FFT + 1, HOP * 8):
        X = np.fft.rfft(x[i:i + FFT] * w)
        Y = np.fft.rfft(y[i:i + FFT] * w)
        for k in range(a, b, 4):
            num += X[k].real * Y[k].real + X[k].imag * Y[k].imag
            den1 += abs(X[k]) ** 2
            den2 += abs(Y[k]) ** 2
    return num / np.sqrt(den1 * den2 + 1e-30)


def main(path):
    d, sr = sf.read(path, dtype="float32", always_2d=True)
    L, R = d[:, 0].astype(np.float64), d[:, 1].astype(np.float64)
    mono = d.mean(axis=1)

    print(f"sr={sr} dur={len(mono)/sr:.2f}s")

    print("\n===== 分带 L/R 相关系数 =====")
    for lo, hi in [(100, 400), (400, 1000), (1000, 3000), (3000, 6000),
                   (6000, 10000), (10000, 16000), (250, 16000)]:
        print(f"  {lo:>6}-{hi:<6}Hz corr={band_corr(L, R, sr, lo, hi):+.4f}")

    print("\n===== 时域动态 =====")
    for name, sig in [("L", L), ("mono", mono)]:
        peak = np.abs(sig).max()
        rms = np.sqrt((sig ** 2).mean())
        # 分段 RMS 动态（1s 段）
        n = sr
        seg = np.array([np.sqrt((sig[i:i+n] ** 2).mean()) for i in range(0, len(sig)-n, n)])
        segdb = 20*np.log10(seg/np.abs(sig).max()+1e-30)
        print(f"  {name}: 峰值={peak:.3f} RMS={rms:.4f} 波峰因数={20*np.log10(peak/rms):.1f}dB "
              f"1s段动态(p95-p05)={np.percentile(segdb,95)-np.percentile(segdb,5):.1f}dB")

    print("\n===== 谱通量/起音规律（全曲）=====")
    w = np.hanning(FFT)
    flux = []
    for i in range(0, len(mono) - FFT + 1, HOP):
        S = np.abs(np.fft.rfft(mono[i:i+FFT] * w))
        flux.append(S)
    F = np.array(flux)
    dF = np.maximum(0, np.diff(F, axis=0)).sum(axis=1)
    dF = dF / (dF.max() + 1e-30)
    peaks = [i for i in range(1, len(dF)-1) if dF[i] > dF[i-1] and dF[i] >= dF[i+1] and dF[i] > 0.05]
    iv = np.diff(peaks) * (HOP / sr)
    print(f"  起音数={len(peaks)} 间隔均值={iv.mean()*1000:.1f}ms 标准差={iv.std()*1000:.1f}ms "
          f"CV={iv.std()/iv.mean():.3f}")

    print("\n===== 全曲平均谱关键点 =====")
    acc = np.zeros(FFT // 2 + 1)
    nb = 0
    for i in range(0, len(mono) - FFT + 1, HOP):
        acc += np.abs(np.fft.rfft(mono[i:i+FFT] * w)) ** 2
        nb += 1
    db = 10*np.log10(acc/nb + 1e-12); db -= db.max()
    bh = sr / FFT
    for f in [8000, 12000, 14000, 16000, 17000, 17500, 18000, 18200, 18400, 18600, 19000, 20000, 22000, 23000]:
        print(f"  {f:>6}Hz {db[int(f/bh)]:>7.1f}dB")
    print(f"  全曲块数={nb}")


if __name__ == "__main__":
    main(sys.argv[1])
