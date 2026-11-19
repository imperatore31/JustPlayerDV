# HRIR asset of the headphone virtual surround

`app/src/main/assets/hrir_kemar.bin` holds the head-related impulse responses used by the
headphone virtual surround (`com.brouken.player.audio`). They come from the MIT KEMAR
measurements:

> W. G. Gardner and K. D. Martin, "HRTF measurements of a KEMAR", Journal of the Acoustical
> Society of America 97 (6), 1995, 3907-3908. MIT Media Lab Perceptual Computing, Technical
> Report #280. The data may be used freely, provided the authors are cited.

The measurements (normal pinna, 710 directions, 44.1 kHz) are taken from the copy shipped with
the [slab](https://pypi.org/project/slab/) Python package (`slab/data/mit_kemar_normal_pinna.bz2`).

To rebuild the asset (Python 3 with numpy and scipy):

```
pip download --no-deps slab && unzip slab-*.whl 'slab/data/*'
python3 make_hrir_asset.py slab/data/mit_kemar_normal_pinna.bz2 ../../app/src/main/assets/hrir_kemar.bin
```

Processing: diffuse-field equalisation, 1/6 octave smoothing, pinna notches limited to 5 dB below a
2 octave envelope (they sound like a veil with another head), flat low end, minimum phase with
the measured interaural delays, 48 kHz, 256 taps, int16. See `make_hrir_asset.py`.
