"""生成器指纹核验：调制域帧率周期性、14-16kHz 能量簇、16k 以上网格纹波。

对照公开的 Suno/Udio 指纹描述，逐条在本文件上验证，只把确实命中的量写入判据。
包络必须以足够高的帧率采样才能观测 50Hz 调制（帧率需 >100fps）。
"""

from __future__ import annotations

import sys

import numpy as np
import soundfile as sf

FFT = 4096
ENV_HOP = 128          # 48k 下包络帧率 375fps，调制域奈奎斯特 187.5Hz
BAND_HOP = 512         # 带能量包络帧率 93.75fps，奈奎斯特 46.9Hz


def welch_db(mono, sr, win):
    acc = np.zeros(FFT // 2 + 1)
    cnt = 0
    for i in range(0, len(mono) - FFT + 1, FFT // 2):
        acc += np.abs(np.fft.rfft(mono[i:i + FFT] * win)) ** 2
        cnt += 1
    db = 10 * np.log10(acc / max(cnt, 1) + 1e-12)
    return db - db.max()


def mod_peak_ratio(sig, sr, target_hz, hop):
    """把序列当调制信号处理，返回 target_hz 附近峰值相对低频中位的 dB。"""
    x = sig - sig.mean()
    fr = sr / hop
    n = min(len(x), 1 << 16)
    x = x[:n] * np.hanning(n)
    spec = np.abs(np.fft.rfft(x)) ** 2
    df = fr / n
    k = int(target_hz / df)
    lo, hi = max(k - max(int(4 / df), 1), 1), min(k + max(int(4 / df), 1), len(spec) - 1)
    med = np.median(spec[1:len(spec) // 4])
    return 10 * np.log10(spec[lo:hi].max() / (med + 1e-30) + 1e-30)


def main(path):
    d, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = d.mean(axis=1)
    bh = sr / FFT
    w = np.hanning(FFT)

    print(f"包络帧率={sr/ENV_HOP:.1f}fps(奈奎斯特{sr/ENV_HOP/2:.1f}Hz) "
          f"带能量帧率={sr/BAND_HOP:.1f}fps(奈奎斯特{sr/BAND_HOP/2:.1f}Hz)")

    # 全带包络（短 hop）→ 调制谱
    env = np.abs(mono[::ENV_HOP]).astype(np.float64)
    # 轻度平滑，抑制单样本尖刺
    k = np.ones(16) / 16
    env = np.convolve(env, k, mode="same")
    print("\n包络调制谱峰值（相对低频中位）:")
    for f in [20, 25, 30, 40, 50, 60, 75, 100]:
        print(f"  {f:>3}Hz: {mod_peak_ratio(env, sr, f, ENV_HOP):+.2f}dB")

    print("\n各带包络的 46.9Hz 内调制峰值:")
    for lo, hi in [(250, 2000), (2000, 8000), (8000, 16000)]:
        a, b = int(lo / bh), int(hi / bh)
        e = np.array([np.sum(np.abs(np.fft.rfft(mono[i:i + FFT] * w)[a:b]) ** 2)
                      for i in range(0, len(mono) - FFT + 1, BAND_HOP)])
        print(f"  {lo}-{hi}Hz: {mod_peak_ratio(np.log10(e + 1e-20), sr, 46.9, BAND_HOP):+.2f}dB")

    db = welch_db(mono, sr, w)
    print("\n14.0-16.4kHz 局部峰突出度:")
    a, b = int(13800 / bh), int(16400 / bh)
    best = []
    for i in range(a, b):
        dev = db[i] - np.median(db[max(i - 40, 0):i + 40])
        best.append((dev, i * bh))
    best.sort(reverse=True)
    for dev, f in best[:8]:
        print(f"  {f:>7.0f}Hz 相对邻域 {dev:+.1f}dB")

    print("\n8-16kHz 谱平坦度（数字雾=能量过于均匀）:")
    a, b = int(8000 / bh), int(16000 / bh)
    seg = db[a:b]
    print(f"  标准差={seg.std():.2f}dB  p95-p05={np.percentile(seg,95)-np.percentile(seg,5):.2f}dB")

    print("\n16.0-18.3kHz 细结构自相关（网格纹波检查）:")
    a, b = int(15900 / bh), int(18300 / bh)
    seg = db[a:b]
    dev = seg - np.convolve(seg, np.ones(31) / 31, mode="same")
    dev = dev - dev.mean()
    ac = np.correlate(dev, dev, "full")[len(dev) - 1:]
    ac /= ac[0]
    top = np.argsort(ac[3:80])[::-1][:6] + 3
    for kk in sorted(top):
        print(f"  lag={kk}bin ({kk*bh:.1f}Hz) ac={ac[kk]:+.3f}")


if __name__ == "__main__":
    main(sys.argv[1])
