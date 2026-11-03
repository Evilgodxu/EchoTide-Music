"""AI 音乐判据复现与诊断：在真实文件上跑通现有三条征象，并输出新增特征量。

与 Kotlin 侧 SpectralDecoder.Accumulator 保持同一口径：4096 点 FFT、Hann 窗、
50% 重叠 Welch、平均功率谱。用于在改代码前先取得可复现的量化依据。
"""

from __future__ import annotations

import sys

import numpy as np
import soundfile as sf

FFT_SIZE = 4096
HOP = FFT_SIZE // 2
PROBE_DURATION_S = 4.0
PROBE_POSITIONS = (0.15, 0.45, 0.75)


def load_probes(path: str):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    print(f"decoded: {sr}Hz {data.shape[1]}ch {data.shape[0]/sr:.2f}s")
    mono = data.mean(axis=1)
    win = np.hanning(FFT_SIZE)
    power_sum = np.zeros(FFT_SIZE // 2 + 1, dtype=np.float64)
    blocks = 0
    for pos in PROBE_POSITIONS:
        start = int(pos * len(mono))
        seg = mono[start:start + int(PROBE_DURATION_S * sr)]
        for i in range(0, len(seg) - FFT_SIZE + 1, HOP):
            frame = seg[i:i + FFT_SIZE] * win
            spec = np.fft.rfft(frame)
            power_sum += (spec.real ** 2 + spec.imag ** 2)
            blocks += 1
    print(f"welch blocks = {blocks}")
    return data, sr, power_sum, blocks


def to_db(power_sum, blocks):
    db = 10 * np.log10(power_sum / blocks + 1e-12)
    return db - db.max()


def stereo_corr(data, sr):
    """复用 Kotlin 侧一阶高通(alpha=0.97)后的一二阶矩相关系数。"""
    alpha = 0.97

    def hp(x):
        y = np.empty_like(x)
        px = 0.0
        py = 0.0
        for i in range(len(x)):
            py = alpha * (py + float(x[i]) - px)
            px = float(x[i])
            y[i] = py
        return y

    x = hp(data[:, 0])
    y = hp(data[:, 1])
    n = len(x)
    dx = np.sum(x * x) - x.sum() ** 2 / n
    dy = np.sum(y * y) - y.sum() ** 2 / n
    return float((np.sum(x * y) - x.sum() * y.sum() / n) / np.sqrt(dx * dy))


def detect_notch(db, sr):
    """征象②：12-18kHz 深凹 + 上方回升。返回 (bestDip, freqHz)。"""
    bin_hz = sr / FFT_SIZE
    n = len(db) - 1
    lo = max(int(12000 / bin_hz), 1)
    hi = min(int(18000 / bin_hz), n)
    if hi - lo < 64:
        return 0.0, 0.0
    half = max(int(800 / bin_hz), 24)
    side = max(int(250 / bin_hz), 8)
    recover = max(int(1500 / bin_hz), 48)
    best, best_f = 0.0, 0.0
    for m in range(lo + half, hi - half + 1):
        w = db[max(m - half, 0):min(m + half, n) + 1]
        if w.min() < db[m]:
            continue
        left = db[max(m - 2 * side, 0):m - side]
        right = db[m + side:min(m + recover, n) + 1]
        if left.size and right.size:
            l_avg, r_avg = left.mean(), right.mean()
            dip = max(l_avg, r_avg) - db[m]
            if dip > best and r_avg >= db[m] + 2.0:
                best, best_f = dip, m * bin_hz
    return float(best), float(best_f)


def detect_comb(db, sr):
    """征象③：1-8kHz 剥离下包络后的等距峰列。返回 (峰数, 峰距CV, 前若干峰频)。"""
    bin_hz = sr / FFT_SIZE
    n = len(db) - 1
    lo = max(int(1000 / bin_hz), 1)
    hi = min(int(8000 / bin_hz), n)
    if hi - lo < 256:
        return 0, float("inf"), []
    radius = max(int(500 / bin_hz), 24)
    env = np.empty(n + 1)
    for i in range(n + 1):
        env[i] = db[max(i - radius, 0):min(i + radius, n) + 1].min()
    peaks: list[int] = []
    prev = -10**9
    for i in range(lo, hi):
        if db[i] >= db[i - 1] and db[i] >= db[i + 1] and db[i] - env[i] >= 4.0 and i - prev >= 6:
            peaks.append(i)
            prev = i
    if len(peaks) < 6:
        return len(peaks), float("inf"), []
    d = np.diff(peaks).astype(float)
    cv = float(d.std() / d.mean()) if d.mean() > 0 else float("inf")
    return len(peaks), cv, [round(p * bin_hz) for p in peaks[:12]]


def spectral_profile(db, sr):
    """内容截止、过渡带宽度、稳健噪底、分带能量——砖墙/升频判据与诊断共用。"""
    bin_hz = sr / FFT_SIZE
    n = len(db) - 1
    nyq = sr / 2
    seg = np.sort(db[int(0.97 * nyq / bin_hz):int(0.995 * nyq / bin_hz) + 1])
    floor = float(seg[len(seg) // 2])
    above = np.where(db - floor > 3.0)[0]
    cut_bin = int(above.max()) if above.size else 0
    cut_hz = cut_bin * bin_hz
    w20 = np.where(db[:cut_bin + 1] - floor > 20.0)[0]
    wall = float((cut_bin - int(w20.max())) * bin_hz) if w20.size else float("inf")
    bands = {}
    for name, lo_hz, hi_hz in [
        ("sub-60", 0, 60), ("60-250", 60, 250), ("250-1k", 250, 1000),
        ("1k-4k", 1000, 4000), ("4k-8k", 4000, 8000), ("8k-12k", 8000, 12000),
        ("12k-16k", 12000, 16000), ("16k-20k", 16000, 20000), ("20k-nyq", 20000, nyq),
    ]:
        a, b = int(lo_hz / bin_hz), min(int(hi_hz / bin_hz), n)
        if b > a:
            bands[name] = round(float(db[a:b].mean()), 1)
    return floor, cut_hz, wall, bands


def per_frame_high_band(data, sr, lo_hz, hi_hz):
    """逐帧高频带能量动态（dither/死区/C2PA 之类水印的帧间稳定性诊断）。"""
    mono = data.mean(axis=1)
    win = np.hanning(FFT_SIZE)
    bin_hz = sr / FFT_SIZE
    a, b = int(lo_hz / bin_hz), int(hi_hz / bin_hz)
    vals = []
    for i in range(0, len(mono) - FFT_SIZE + 1, HOP):
        spec = np.fft.rfft(mono[i:i + FFT_SIZE] * win)
        vals.append(float((spec.real[a:b] ** 2 + spec.imag[a:b] ** 2).sum()))
    v = np.array(vals)
    p = 10 * np.log10(v + 1e-12)
    return float(np.percentile(p, 95) - np.percentile(p, 5))


def main(path: str):
    data, sr, ps, blocks = load_probes(path)
    db = to_db(ps, blocks)
    bin_hz = sr / FFT_SIZE

    corr = stereo_corr(data, sr)
    dip, dip_f = detect_notch(db, sr)
    npeaks, cv, peak_fs = detect_comb(db, sr)
    floor, cut_hz, wall, bands = spectral_profile(db, sr)

    print("\n===== 现有三征象 =====")
    print(f"① 立体声相关性 = {corr:.4f}  (阈值>=0.93 命中)")
    print(f"② 12-18k 缺口深 = {dip:.2f}dB @ {dip_f:.0f}Hz  (阈值>=4.5 且需回升)")
    print(f"③ 谐波梳峰数 = {npeaks}, 峰距CV = {cv:.3f}  (阈值峰>=6 且 CV<=0.30)")
    hits = sum([corr >= 0.93, dip >= 4.5, npeaks >= 6 and cv <= 0.30])
    print(f"==> 命中 {hits}/3 征象，现有判定需 >=2 才判 AI: {'AI' if hits>=2 else '未检出(漏检)'}")

    print("\n===== 频谱形态诊断 =====")
    print(f"稳健噪底 = {floor:.1f}dB, 内容截止 = {cut_hz:.0f}Hz, 过渡带 = {wall:.0f}Hz, 奈奎斯特 = {sr/2:.0f}Hz")
    print(f"截止/奈奎斯特 = {cut_hz/(sr/2)*100:.1f}%   距 16k = {cut_hz-16000:.0f}Hz")
    print("分带能量(dB):", bands)

    print("\n===== 逐帧高频带动态 =====")
    for lo, hi in [(16000, 18000), (18000, 20000), (20000, 23000), (22150, 22850)]:
        print(f"  {lo}-{hi}Hz 动态(p95-p05) = {per_frame_high_band(data, sr, lo, hi):.1f}dB")

    print("\n===== 谱图尖峰/齿状诊断(1-8k 残差谱) =====")
    radius = max(int(500 / bin_hz), 24)
    n = len(db) - 1
    env = np.array([db[max(i - radius, 0):min(i + radius, n) + 1].min() for i in range(n + 1)])
    resid = db - env
    a, b = int(1000 / bin_hz), int(8000 / bin_hz)
    print(f"1-8k 残差谱: 均值 {resid[a:b].mean():.2f}dB, 最大 {resid[a:b].max():.2f}dB, "
          f"标准差 {resid[a:b].std():.2f}dB")
    top = np.argsort(resid[a:b])[-20:][::-1] + a
    print("残留最强 20 峰(Hz):", sorted(round(int(p) * bin_hz) for p in top))


if __name__ == "__main__":
    main(sys.argv[1])
