"""Reads the MIT KEMAR HRTF data shipped (as a pickled object) with the "slab" Python package,
without running any code from the pickle: only the slab HRTF / Filter containers (replaced by
plain stubs) and numpy arrays are allowed."""
import bz2, pickle, sys
import numpy as np
from numpy._core import multiarray

class Stub:
    pass

class SafeUnpickler(pickle.Unpickler):
    ALLOWED = {
        ('slab.hrtf', 'HRTF'): type('HRTF', (Stub,), {}),
        ('slab.filter', 'Filter'): type('Filter', (Stub,), {}),
        ('numpy.core.multiarray', '_reconstruct'): multiarray._reconstruct,
        ('numpy', 'ndarray'): np.ndarray,
        ('numpy', 'dtype'): np.dtype,
    }
    def find_class(self, module, name):
        key = (module, name)
        if key in self.ALLOWED:
            return self.ALLOWED[key]
        raise pickle.UnpicklingError('blocked %s.%s' % key)

def load(path):
    return SafeUnpickler(bz2.BZ2File(path)).load()

if __name__ == '__main__':
    h = load(sys.argv[1])
    print({k: type(v).__name__ for k, v in vars(h).items()})
    print('samplerate', h.samplerate, 'n', len(h.data))
    f0 = h.data[0]
    print({k: (type(v).__name__, getattr(v, 'shape', v) if not isinstance(v, (int, float, str, bool)) else v) for k, v in vars(f0).items()})
    src = np.asarray(h.sources)
    print('sources', src.shape, src[:5], src[-3:])
    print('listener', getattr(h, 'listener', None))
