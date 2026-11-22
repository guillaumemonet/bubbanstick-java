package bubba.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * SPAK sound banks ({@code disk1.spk}, {@code disk2.spk}): "SPAK", n, n packed sizes, n 24-byte
 * entries (the game's sound records, code 0x7BC8), then per sample delta-packed PCM: a 3-bit
 * width selector per 8 samples (0 = repeat), signed 2..8-bit deltas, MSB-first 16-bit words.
 */
final class Spak {

    /** One sample: signed 8-bit PCM and the 12 words of its entry. */
    record Sample(byte[] pcm, int[] entry) {
    }

    private Spak() {
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) << 8 | (b[o + 1] & 0xff);
    }

    static List<Sample> parse(byte[] b) {
        if (b[0] != 'S' || b[1] != 'P' || b[2] != 'A' || b[3] != 'K') {
            throw new IllegalStateException("not a SPAK bank");
        }
        int n = u16(b, 4);
        int t = 6 + 2 * n, p = t + 24 * n;
        List<Sample> res = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] e = new int[12];
            for (int k = 0; k < 12; k++) {
                e[k] = u16(b, t + 24 * i + 2 * k);
            }
            res.add(new Sample(depack(b, p, e[4] * 2), e));
            p += u16(b, 6 + 2 * i);
        }
        return res;
    }

    private static byte[] depack(byte[] d, int p, int count) {
        byte[] out = new byte[count];
        int k = 0, acc = 0, n = 0;
        long bits = 0;
        while (count > 0) {
            int m = Math.min(8, count);
            count -= m;
            // read 3 bits
            int[] st = {p, n};
            long[] bb = {bits};
            int sel = get(d, st, bb, 3);
            if (sel == 0) {
                for (int j = 0; j < m; j++) {
                    out[k++] = (byte) acc;
                }
            } else {
                int w = sel + 1;
                for (int j = 0; j < m; j++) {
                    int v = get(d, st, bb, w);
                    if ((v & (1 << (w - 1))) != 0) {
                        v -= 1 << w;
                    }
                    acc = (acc - v) & 0xff;
                    out[k++] = (byte) acc;
                }
            }
            p = st[0];
            n = st[1];
            bits = bb[0];
        }
        return out;
    }

    /** Next {@code w} bits; st = {pointer, bits available}, bb = {accumulator}. */
    private static int get(byte[] d, int[] st, long[] bb, int w) {
        while (st[1] < w) {
            int v = st[0] + 1 < d.length ? u16(d, st[0]) : 0;
            bb[0] = ((bb[0] << 16) | v) & 0xffffffffL;
            st[0] += 2;
            st[1] += 16;
        }
        st[1] -= w;
        return (int) ((bb[0] >> st[1]) & ((1 << w) - 1));
    }
}
