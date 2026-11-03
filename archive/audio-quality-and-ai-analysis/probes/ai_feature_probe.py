"""AI 专属特征探查：频谱微结构周期性、M/S 相关、谐噪比代理。

现有三条征象判据都基于固定阈值组合，本脚本改从「生成伪影」的物理来源入手，
统计与有损转码可区分的量，为判据设计取数。
"""

from __future__ import annotations

import sys

import numpy as np
import soundfile as sf

FFT_SIZE = 4096
HOP = FFT_SIZE // 2
POS = (0.15, 0.45, 0.75)


def welch(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    win = np.hanning(FFT_SIZE)
    acc = np.zeros(FFT_SIZE // 2 + 1)
    blocks = 0
    for p in POS:
        s = int(p * len(mono))
        seg = mono[s:s + int(4.0 * sr)]
        for i in range(0, len(seg) - FFT_SIZE + 1, HOP):
            acc += np.abs(np.fft.rfft(seg[i:i + FFT_SIZE] * win)) ** 2
            blocks += 1
    return data, sr, acc / blocks


def db_peak(x):
    d = 10 * np.log10(x + 1e-12)
    return d - d.max()


def fine_structure_periodicity(db, sr, lo_hz, hi_hz):
    """细结构周期性：剥离滚动最小值包络后对残差做自相关，找规则伪影周期。

    神经声码器/扩散模型的上采样层会在频谱留下等间距纹波；有损转码的
    量化噪声在平均谱上近似白噪，自相关无显著周期峰。
    """
    bin_hz = sr / FFT_SIZE
    n = len(db) - 1
    a, b = int(lo_hz / bin_hz), min(int(hi_hz / bin_hz), n)
    radius = max(int(300 / bin_hz), 16)
    env = np.array([db[max(i - radius, 0):min(i + radius, n) + 1].min() for i in range(a, b + 1)])
    res = db[a:b + 1] - env
    res = res - res.mean()
    ac = np.correlate(res, res, mode="full")[len(res) - 1:]
    ac /= ac[0]
    return ac, bin_hz


def ms_correlation(data, sr, lo_hz, hi_hz):
    """M/S 视角：中侧相关与侧/中能量比。真人混音侧信道含真实空间信息。"""
    x = data[:, 0].astype(np.float64)
    y = data[:, 1].astype(np.float64)
    mid = (x + y) / 2
    side = (x - y) / 2
    # 分带能量需要滤波：用 rFFT 逐帧统计带内能量比
    win = np.hanning(FFT_SIZE)
    bin_hz = sr / FFT_SIZE
    a, b = int(lo_hz / bin_hz), int(hi_hz / bin_hz)
    num = 0.0
    den = 0.0
    n = min(len(mid), len(side))
    for i in range(0, n - FFT_SIZE + 1, HOP * 4):
        mf = np.fft.rfft(mid[i:i + FFT_SIZE] * win)
        sf_ = np.fft.rfft(side[i:i + FFT_SIZE] * win)
        den += np.sum(np.abs(mf[a:b]) ** 2)
        num += np.sum(np.abs(sf_[a:b]) ** 2)
    return num, den


def harmonics_to_noise(db, sr, f_est=None):
    """谐噪比代理：对局部窗口取峰与本底的落差，衡量谐波是否「过于干净」。"""
    bin_hz = sr / FFT_SIZE
    n = len(db) - 1
    a, b = int(300 / bin_hz), min(int(10000 / bin_hz), n)
    seg = db[a:b]
    # 用矩形结构元做形态学开运算去掉峰，闭运算保留峰顶
    radius = max(int(60 / bin_hz), 4)
    opened = np.array([seg[max(i - radius, 0):min(i + radius, len(seg) - 1) + 1].min()
                       for i in range(len(seg))])
    peak_prom = seg - opened
    return float(peak_prom.mean()), float(np.percentile(peak_prom, 95)), float(peak_prom.std())


def main(path):
    data, sr, ps = welch(path)
    db = db_peak(ps)
    bin_hz = sr / FFT_SIZE
    print(f"sr={sr} binHz={bin_hz:.3f}")

    print("\n===== 细结构自相关（1-8kHz 残差）=====")
    ac, bh = fine_structure_periodicity(db, sr, 1000, 8000)
    top = np.argsort(ac[2:60])[::-1][:10] + 2
    for lag in sorted(top):
        print(f"  lag={lag:>3}bin ({lag*bh:>7.1f}Hz)  ac={ac[lag]:.3f}")

    print("\n===== 细结构自相关（300Hz-1kHz）=====")
    ac2, bh2 = fine_structure_periodicity(db, sr, 300, 1000)
    top2 = np.argsort(ac2[2:60])[::-1][:10] + 2
    for lag in sorted(top2):
        print(f"  lag={lag:>3}bin ({lag*bh2:>7.1f}Hz)  ac={ac2[lag]:.3f}")

    print("\n===== M/S 侧中能量比 =====")
    for lo, hi in [(100, 500), (500, 2000), (2000, 8000), (8000, 16000)]:
        num, den = ms_correlation(data, sr, lo, hi)
        print(f"  {lo}-{hi}Hz  侧/中 = {10*np.log10(num/den + 1e-15):.1f}dB")

    print("\n===== 谐波突出度（300Hz-10kHz）=====")
    mean_p, p95, std = harmonics_to_noise(db, sr)
    print(f"  峰突出度 均值={mean_p:.2f}dB p95={p95:.2f}dB 标准差={std:.2f}dB")

    print("\n===== 截止区精细形态 =====")
    a = int(17000 / bin_hz)
    b = min(int(20000 / bin_hz), len(db) - 1)
    for i in range(a, b + 1, 4):
        print(f"  {i*bin_hz:>7.0f}Hz  {db[i]:>7.1f}dB")

    print("\n===== 20-24kHz 死区绝对值 =====")
    a = int(20000 / bin_hz)
    print(f"  死区均值 = {db[a:].mean():.1f}dB, 最大 = {db[a:].max():.1f}dB, "
          f"最小 = {db[a:].min():.1f}dB, 标准差 = {db[a:].std():.2f}dB")


if __name__ == "__main__":
    main(sys.argv[1])
