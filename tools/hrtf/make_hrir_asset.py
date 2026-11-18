"""Builds the HRIR asset of the virtual surround engine from the MIT KEMAR measurements.

Steps: diffuse-field equalisation, 1/6 octave smoothing, flat low end (no ILD below ~200 Hz, as
for a real head), minimum phase reconstruction with the measured interaural delay restored with
sub-sample precision, resampling to 48 kHz (spectral), int16 quantisation. Only the left ear is
stored: the measurements are symmetric (right ear at azimuth a == left ear at -a).

Usage: python3 make_hrir_asset.py mit_kemar_normal_pinna.bz2 ../../app/src/main/assets/hrir_kemar.bin
(the KEMAR data comes with the "slab" Python package, see README.md)
"""
import struct, sys
import numpy as np
from scipy import signal
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import load_kemar

SRC = sys.argv[1]
OUT = sys.argv[2]
FS_IN, FS_OUT = 44100.0, 48000
NFFT = 4096
TAPS = 256
MARGIN = 24  # samples before the head-centre arrival time (room for pre-ringing of fractional delays)

h = load_kemar.load(SRC)
src = np.asarray(h.sources)
D = np.stack([f.data for f in h.data])  # (dirs, 512, 2) left/right
left = D[:, :, 0]

# ---- solid angle weights for the diffuse-field average
w = np.zeros(len(src))
for e in sorted(set(src[:, 1])):
    m = src[:, 1] == e
    w[m] = (np.cos(np.deg2rad(e)) / m.sum()) if e < 90 else (1 - np.cos(np.deg2rad(5))) / (2 * np.deg2rad(10))
w /= w.sum()

f_in = np.fft.rfftfreq(NFFT, 1 / FS_IN)
H = np.fft.rfft(left, NFFT, axis=1)
P = np.abs(H) ** 2

def smooth(power, frac):
    """Fractional-octave smoothing of power spectra along the last axis (f_in grid)."""
    out = np.empty_like(power)
    for k, fk in enumerate(f_in):
        if fk <= 0:
            out[..., k] = power[..., k]
            continue
        lo, hi = fk * 2 ** (-frac / 2), fk * 2 ** (frac / 2)
        sel = (f_in >= lo) & (f_in <= hi)
        out[..., k] = power[..., sel].mean(axis=-1)
    return out

P_df = smooth((w[:, None] * P).sum(axis=0), 1 / 3)
P_s = smooth(P, 1 / 6)
mag = np.sqrt(P_s / P_df[None, :])
# limit the equalisation where the measurement has no energy (top octave): roll off gently above 16 kHz
roll = np.where(f_in > 16000, np.clip(1 - (f_in - 16000) / 6000, 0.05, 1), 1.0)
mag *= roll[None, :]
# flat low end: no head shadow below ~200 Hz (crossfade 200..500 Hz in log magnitude)
t = np.clip((np.log(np.maximum(f_in, 1)) - np.log(200)) / (np.log(500) - np.log(200)), 0, 1)
t = t * t * (3 - 2 * t)
logmag = np.log(np.maximum(mag, 1e-4))
logmag = logmag * t[None, :]
# normalise: diffuse-field average gain of 0 dB in 500 Hz..8 kHz
band = (f_in > 500) & (f_in < 8000)
logmag -= np.log(np.sqrt((w[:, None] * np.exp(2 * logmag[:, band])).sum(axis=0).mean()))

# ---- interaural delays: onset of the 3 kHz low-passed responses (threshold -20 dB of the peak)
sos = signal.butter(4, 3000, fs=FS_IN, output='sos')
up = 16
def onset(x):
    y = signal.sosfiltfilt(sos, x)
    y = signal.resample_poly(y, up, 1)
    a = np.abs(y)
    i = int(np.argmax(a > 0.1 * a.max()))
    return i / (up * FS_IN)
onsets = np.array([onset(x) for x in left])
i_front = int(np.argmin(np.abs(((src[:, 0] + 180) % 360) - 180) + 100 * np.abs(src[:, 1])))
t_ref = onsets[i_front]  # head-centre reference: frontal arrival (equal at both ears)
delay = onsets - t_ref
print('relative onsets ms: min %.3f max %.3f' % (delay.min() * 1e3, delay.max() * 1e3))

# ---- minimum phase at 48 kHz, spectral resampling (magnitude interpolated on a log-frequency axis)
N2 = 4096
f_out = np.fft.rfftfreq(N2, 1 / FS_OUT)
irs = np.zeros((len(src), TAPS))
max_truncated = 0
for d in range(len(src)):
    lm = np.interp(f_out, f_in, logmag[d])  # beyond 22.05 kHz: holds the (rolled off) last value
    lm = np.where(f_out > 21000, lm + np.log(np.clip(1 - (f_out - 21000) / 3000, 1e-3, 1)), lm)
    full = np.concatenate([lm, lm[-2:0:-1]])
    cep = np.fft.ifft(full).real
    fold = np.zeros(N2)
    fold[0] = cep[0]
    fold[1:N2 // 2] = 2 * cep[1:N2 // 2]
    fold[N2 // 2] = cep[N2 // 2]
    Hmin = np.exp(np.fft.rfft(fold))
    tau = MARGIN / FS_OUT + delay[d]
    Hd = Hmin * np.exp(-2j * np.pi * f_out * tau)
    ir = np.fft.irfft(Hd, N2)
    irs[d] = ir[:TAPS]
    tail = ir[TAPS:N2 // 2]
    max_truncated = max(max_truncated, np.sum(tail ** 2) / np.sum(ir ** 2))
print('largest energy lost by truncation: %.2g' % max_truncated)
fade = np.ones(TAPS)
fade[-48:] = 0.5 * (1 + np.cos(np.linspace(0, np.pi, 48)))
irs *= fade[None, :]

scale = np.abs(irs).max() / 32767.0
q = np.round(irs / scale).astype('>i2')
with open(OUT, 'wb') as fo:
    fo.write(b'HRIR')
    fo.write(struct.pack('>iiiifi', 1, FS_OUT, len(src), TAPS, scale, MARGIN))
    for d in range(len(src)):
        fo.write(struct.pack('>ff', src[d, 0], src[d, 1]))
        fo.write(q[d].tobytes())
print('directions', len(src), 'taps', TAPS, 'scale', scale, 'bytes', 4 + 24 + len(src) * (8 + 2 * TAPS))
