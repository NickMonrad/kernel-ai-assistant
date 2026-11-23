import numpy as np
import verify_unpadded as v
import pc_kokoro as ref
voice = ref.load_voice("af_heart")
for p in v.PHRASES:
    _, a, _ = v.run(p, voice, 128, exact=False)
    _, b, _ = v.run(p, voice, 128, exact=True)
    n = a.astype(np.float64) - b.astype(np.float64)
    snr = 10 * np.log10(np.sum(a.astype(np.float64) ** 2) / np.sum(n ** 2))
    print(f"SNR_dB={snr:.2f} corr={np.corrcoef(a, b)[0,1]:.7f} max_abs={np.abs(n).max():.3e}")
