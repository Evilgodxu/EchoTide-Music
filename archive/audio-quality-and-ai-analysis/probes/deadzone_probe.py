"""死区性质判定：截止上方高频是「真数字静默」还是「转码噪底」。

这是区分 AI 生成带限与有损转码带限的关键物理量：
- 有损转码（MP3/AAC→FLAC）在截止上方留下量化/重采样噪底，样本非零；
- 生成模型直接输出带限波形时，截止上方可为严格零值（编码器整段置零）。
逐帧统计高频带是否恒为零，并给出死区绝对电平。
"""

from __future__ import annotations

import sys

import numpy as np
import soundfile as sf

FFT_SIZE = 4096
HOP = FFT_SIZE // 2


def main(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    bin_hz = sr / FFT_SIZE
    win = np.hanning(FFT_SIZE)

    print(f"sr={sr}  样本峰值={np.abs(data).max():.6f} (1.0=满幅)")

    for lo_hz, hi_hz in [(18700, 24000), (19000, 24000), (20000, 24000), (17000, 24000)]:
        a, b = int(lo_hz / bin_hz), FFT_SIZE // 2
        zero_frames = 0
        total = 0
        max_mag = 0.0
        rms_acc = 0.0
        for i in range(0, len(mono) - FFT_SIZE + 1, HOP):
            spec = np.fft.rfft(mono[i:i + FFT_SIZE] * win)
            mag = np.abs(spec[a:b])
            if mag.max() == 0.0:
                zero_frames += 1
            max_mag = max(max_mag, float(mag.max()))
            rms_acc += float(np.sum(spec[a:b] ** 2))
            total += 1
        rms = np.sqrt(rms_acc / total) / (FFT_SIZE / 2)
        print(f"  {lo_hz}-{hi_hz}Hz: 全零帧 {zero_frames}/{total} "
              f"({100*zero_frames/total:.1f}%), 最大幅度={max_mag:.3e}, RMS={rms:.3e}")

    # 频域逐 bin 零值率：找出编码器真正置零的频点范围
    print("\n频域逐 bin 零值率（每隔 100 bin 采样）:")
    n = FFT_SIZE // 2 + 1
    zero_cnt = np.zeros(n)
    total = 0
    for i in range(0, len(mono) - FFT_SIZE + 1, HOP * 4):
        spec = np.fft.rfft(mono[i:i + FFT_SIZE] * win)
        zero_cnt += (np.abs(spec) == 0.0)
        total += 1
    frac = zero_cnt / total
    for i in range(0, n, 100):
        print(f"  {i*bin_hz:>7.0f}Hz 零值率={frac[i]*100:>5.1f}%")
    first_full_zero = np.argmax(frac > 0.99)
    print(f"\n连续全零起点 ≈ {first_full_zero*bin_hz:.0f}Hz (bin {first_full_zero})")


if __name__ == "__main__":
    main(sys.argv[1])
