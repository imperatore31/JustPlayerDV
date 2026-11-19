"""Test signals to capture a hardware headphone virtualizer (e.g. a Creative SXFI DAC).

Each channel plays in turn an exponential sine sweep (20 Hz - 22 kHz, 5 s, -12 dBFS) followed by
2.5 s of silence (room tail and processing latency); the first channel plays again at the end, to
measure the clock drift between the player and the recorder. Record the virtualizer's headphone
output (stereo line-in, 48 kHz) while the file plays, then run extract_responses.py.

Usage: python3 make_test_signals.py OUTPUT_DIR
Writes capture_7.1.flac (FL FR FC LFE BL BR SL SR, the FLAC / WAV order) and capture_stereo.flac.
"""
import os, sys
import numpy as np
import soundfile as sf

FS = 48000
F1, F2 = 20.0, 22000.0
SWEEP_SECONDS = 5.0
GAP_SECONDS = 2.5
LEAD_SECONDS = 1.0
LEVEL = 10 ** (-12 / 20)


def sweep():
    t = np.arange(int(SWEEP_SECONDS * FS)) / FS
    k = np.log(F2 / F1)
    x = np.sin(2 * np.pi * F1 * SWEEP_SECONDS / k * (np.exp(t * k / SWEEP_SECONDS) - 1))
    fade_in = int(0.05 * FS)
    fade_out = int(0.01 * FS)
    x[:fade_in] *= 0.5 - 0.5 * np.cos(np.pi * np.arange(fade_in) / fade_in)
    x[-fade_out:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(fade_out) / fade_out)
    return LEVEL * x


def build(channels, order):
    s = sweep()
    block = len(s) + int(GAP_SECONDS * FS)
    lead = int(LEAD_SECONDS * FS)
    out = np.zeros((lead + block * len(order), channels))
    for i, ch in enumerate(order):
        start = lead + i * block
        out[start:start + len(s), ch] = s
    return out


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    # 7.1: FL FR FC LFE BL BR SL SR, then FL again (drift)
    sf.write(os.path.join(out_dir, 'capture_7.1.flac'), build(8, [0, 1, 2, 3, 4, 5, 6, 7, 0]), FS, subtype='PCM_16')
    sf.write(os.path.join(out_dir, 'capture_stereo.flac'), build(2, [0, 1, 0]), FS, subtype='PCM_16')


if __name__ == '__main__':
    main(sys.argv[1])
