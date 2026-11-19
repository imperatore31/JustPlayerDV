# Capturing a hardware headphone virtualizer

Measures the binaural responses of a headphone virtualizer you own (e.g. a Creative SXFI DAC)
for your own use in DV Player: each virtual speaker is played with a sine sweep while the
virtualizer's headphone output is recorded.

1. `python3 make_test_signals.py OUT_DIR` writes `capture_7.1.flac` (one sweep per channel: FL FR
   FC LFE BL BR SL SR, then FL again) and `capture_stereo.flac` (FL FR FL).
2. Connect the virtualizer's headphone output to a **stereo line input** (3.5 mm jack cable),
   e.g. the line-in of a desktop PC or a USB audio interface. Set the virtualizer as a 7.1
   device if it accepts multichannel audio (Windows: Sound settings, Configure speakers).
3. Record the line input (stereo, 48 kHz, peaks around -6 dBFS, no "enhancements") while the
   file plays through the virtualizer with its effect on.
4. `python3 extract_responses.py RECORDING.wav responses.wav [--stereo]` writes the responses as
   a HeSuVi style 14 channel WAV. Latency and clock drift between player and recorder are
   measured and corrected.

The responses belong to the device and its settings (e.g. its headphone profile); keep them for
yourself. Requires Python 3 with numpy, scipy and soundfile.
