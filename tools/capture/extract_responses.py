"""Extracts the binaural responses of a headphone virtualizer from a capture recording.

The recording is the virtualizer's headphone output (stereo) while it played capture_7.1.flac or
capture_stereo.flac (see make_test_signals.py). Output: a HeSuVi style 14 channel WAV (48 kHz,
32 bit float), the format DV Player imports, with the response of each virtual speaker at both ears:

    FL-L FL-R SL-L SL-R BL-L BL-R FC-L FR-R FR-L SR-R SR-L BR-R BR-L FC-R

Speakers that were not captured (stereo capture) are left silent. Relative timing between the
speakers is kept (one time reference for all), the clock drift between player and recorder is
measured with the repeated first sweep and corrected.

Usage: python3 extract_responses.py RECORDING OUTPUT.wav [--stereo] [--length SECONDS]
"""
import argparse
import os
import sys
import numpy as np
import soundfile as sf
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_test_signals as ts  # noqa: E402

FS = ts.FS
# Capture order (FLAC / WAV channel order) and position of each speaker in the HeSuVi file
CAPTURE_7_1 = ['FL', 'FR', 'FC', 'LFE', 'BL', 'BR', 'SL', 'SR', 'FL']
CAPTURE_STEREO = ['FL', 'FR', 'FL']
HESUVI = {  # speaker: (index of left ear, index of right ear)
    'FL': (0, 1), 'SL': (2, 3), 'BL': (4, 5), 'FC': (6, 13),
    'FR': (8, 7), 'SR': (10, 9), 'BR': (12, 11),
}
PRE_SECONDS = 0.005


def band_window(f, lo0, lo1, hi0, hi1):
    """1 between lo1 and hi0, raised cosine tapers to 0 at lo0 and hi1 (log frequency)."""
    w = np.zeros_like(f)
    with np.errstate(divide='ignore', invalid='ignore'):
        x = np.log(np.maximum(f, 1e-9))
        up = np.clip((x - np.log(lo0)) / (np.log(lo1) - np.log(lo0)), 0, 1)
        down = np.clip((np.log(hi1) - x) / (np.log(hi1) - np.log(hi0)), 0, 1)
    w = (0.5 - 0.5 * np.cos(np.pi * up)) * (0.5 - 0.5 * np.cos(np.pi * down))
    w[f <= 0] = 0
    return w


def deconvolve(recording, sweep):
    """Impulse response of a recording of the sweep (regularised spectral division)."""
    n = 1
    while n < len(recording) + len(sweep):
        n <<= 1
    s = np.fft.rfft(sweep, n)
    f = np.fft.rfftfreq(n, 1 / FS)
    # Exact inverse inside the band of the sweep, smooth tapers at its edges (the sweep has
    # little energy there: dividing would only amplify noise)
    window = band_window(f, ts.F1, ts.F1 * 1.5, ts.F2 * 0.9, ts.F2)
    power = np.abs(s) ** 2
    inverse = np.conj(s) / np.maximum(power, 1e-20 * power.max()) * window
    out = []
    for ch in range(recording.shape[1]):
        out.append(np.fft.irfft(np.fft.rfft(recording[:, ch], n) * inverse, n)[:len(recording)])
    return np.stack(out, axis=1)


def peak_position(ir, around, radius):
    """Sub-sample position of the largest peak (both ears) within around +- radius."""
    lo = max(0, around - radius)
    seg = np.abs(ir[lo:around + radius]).sum(axis=1)
    i = int(np.argmax(seg))
    if 0 < i < len(seg) - 1:
        a, b, c = seg[i - 1], seg[i], seg[i + 1]
        i = i + 0.5 * (a - c) / (a - 2 * b + c)
    return lo + i


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('recording')
    parser.add_argument('output')
    parser.add_argument('--stereo', action='store_true', help='capture made with capture_stereo.flac')
    parser.add_argument('--length', type=float, default=1.0, help='response length, seconds')
    args = parser.parse_args()

    order = CAPTURE_STEREO if args.stereo else CAPTURE_7_1
    rec, fs = sf.read(args.recording, always_2d=True)
    rec = rec[:, :2].astype(np.float64)
    if fs != FS:
        g = np.gcd(int(fs), FS)
        rec = signal.resample_poly(rec, FS // g, int(fs) // g, axis=0)
    peak_level = np.abs(rec).max()
    print('recording: %.1f s, peak %.1f dBFS' % (len(rec) / FS, 20 * np.log10(peak_level + 1e-12)))
    if peak_level > 0.98:
        print('WARNING: the recording clips, record at a lower level')

    sweep = ts.sweep()
    block = len(sweep) + int(ts.GAP_SECONDS * FS)
    lead = int(ts.LEAD_SECONDS * FS)

    # Latency and drift from the first and the repeated first sweep
    ir = deconvolve(rec, sweep)
    search = int(0.5 * FS)
    expected_first = lead
    first = peak_position(ir, expected_first + search, search + int(0.5 * FS))
    last_nominal = first + (len(order) - 1) * block
    last = peak_position(ir, int(round(last_nominal)), int(0.05 * FS))
    drift = (last - first) / ((len(order) - 1) * block) - 1
    print('latency %.1f ms, clock drift %+.1f ppm' % ((first - lead) / FS * 1000, drift * 1e6))
    if abs(drift) > 1e-6:
        # Resample (band-limited) so that the recorder clock matches the player clock
        rec = signal.resample(rec, int(round(len(rec) / (1 + drift))), axis=0)
        ir = deconvolve(rec, sweep)
        first = peak_position(ir, int(round((first) / (1 + drift))), int(0.01 * FS))

    # Noise floor: the silence before the first sweep
    noise = ir[int(0.1 * FS):max(int(0.2 * FS), int(first - 0.6 * FS))]
    noise_rms = np.sqrt(np.mean(noise ** 2)) if len(noise) else 0

    length = int(args.length * FS)
    pre = int(PRE_SECONDS * FS)
    origin = int(round(first)) - pre  # same time reference for every speaker
    out = np.zeros((length, 14), dtype=np.float32)
    fade = int(0.1 * length)
    window = np.ones(length)
    window[-fade:] = 0.5 + 0.5 * np.cos(np.pi * np.arange(fade) / fade)
    seen = set()
    for i, speaker in enumerate(order):
        if speaker in seen:
            continue
        seen.add(speaker)
        start = origin + i * block
        response = ir[start:start + length] * window[:, None]
        peak = np.abs(response).max()
        energy_db = 10 * np.log10(np.sum(response ** 2) + 1e-20)
        snr = 20 * np.log10(peak / noise_rms) if noise_rms > 0 else float('inf')
        delay_ms = (np.argmax(np.abs(response).sum(axis=1)) - pre) / FS * 1000
        print('%-3s peak %.3f, energy %+.1f dB, peak/noise %.0f dB, delay %+.2f ms' % (
            speaker, peak, energy_db, snr, delay_ms))
        if speaker in HESUVI:
            left, right = HESUVI[speaker]
            out[:, left] = response[:, 0]
            out[:, right] = response[:, 1]
    sf.write(args.output, out, FS, subtype='FLOAT')
    print('written', args.output)


if __name__ == '__main__':
    main()
