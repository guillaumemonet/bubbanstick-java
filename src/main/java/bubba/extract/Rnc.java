package bubba.extract;

/**
 * Rob Northen ProPack decompression: method 1 (the data files) and method 2 (the two crunched
 * hunks of the executable).
 */
public final class Rnc {

    private Rnc() {
    }

    /** Result of an unpack: the data and the number of packed bytes used (header included). */
    public record Unpacked(byte[] data, int packedSize) {
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) << 24 | (b[o + 1] & 0xff) << 16 | (b[o + 2] & 0xff) << 8 | (b[o + 3] & 0xff);
    }

    /** CRC-16 (polynomial $A001) stored at +12 of an RNC header. */
    public static int crc16(byte[] b) {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0xa001 : c >>> 1;
            }
            t[i] = c;
        }
        int c = 0;
        for (byte x : b) {
            c = t[(c ^ x) & 0xff] ^ (c >>> 8);
        }
        return c;
    }

    // ------------------------------------------------------------------ method 1

    /** Little-endian word bit stream of method 1. */
    private static final class Bits {
        final byte[] d;
        int p, cnt;
        long buf;

        Bits(byte[] d, int p) {
            this.d = d;
            this.p = p;
            buf = word(p);
            cnt = 16;
        }

        int word(int q) {
            return (d[q] & 0xff) | (d[q + 1] & 0xff) << 8;
        }

        void adv(int n) {
            buf >>>= n;
            cnt -= n;
            if (cnt < 16) {
                p += 2;
                buf |= (long) word(p) << cnt;
                cnt += 16;
            }
        }

        int read(int mask, int n) {
            int r = (int) (buf & mask);
            adv(n);
            return r;
        }

        void fix() {
            cnt -= 16;
            buf &= (1L << cnt) - 1;
            buf |= (long) word(p) << cnt;
            cnt += 16;
        }
    }

    private static int mirror(int x, int n) {
        int r = 0;
        for (int i = 0; i < n; i++) {
            r = (r << 1) | ((x >>> i) & 1);
        }
        return r;
    }

    /** Huffman table: rows of (code, length, mask, value). */
    private static int[][] table(Bits bs) {
        int num = bs.read(0x1f, 5);
        if (num == 0) {
            return new int[0][];
        }
        int[] ll = new int[num];
        int max = 1;
        for (int j = 0; j < num; j++) {
            ll[j] = bs.read(0xf, 4);
            max = Math.max(max, ll[j]);
        }
        java.util.List<int[]> t = new java.util.ArrayList<>();
        int code = 0;
        for (int i = 1; i <= max; i++) {
            for (int j = 0; j < num; j++) {
                if (ll[j] == i) {
                    t.add(new int[]{mirror(code, i), i, (1 << i) - 1, j});
                    code++;
                }
            }
            code <<= 1;
        }
        return t.toArray(new int[0][]);
    }

    private static int huf(Bits bs, int[][] t) {
        for (int[] e : t) {
            if ((bs.buf & e[2]) == e[0]) {
                bs.adv(e[1]);
                int v = e[3];
                if (v >= 2) {
                    int x = 1 << (v - 1);
                    return x | bs.read(x - 1, v - 1);
                }
                return v;
            }
        }
        throw new IllegalStateException("RNC huffman decode error");
    }

    /** Unpack the RNC method 1 block at {@code off}. Throws on malformed data. */
    public static Unpacked unpack1(byte[] src, int off) {
        if (src[off] != 'R' || src[off + 1] != 'N' || src[off + 2] != 'C' || src[off + 3] != 1) {
            throw new IllegalArgumentException("not RNC1");
        }
        int ulen = u32(src, off + 4), plen = u32(src, off + 8);
        if (ulen < 0 || plen < 0 || ulen > 16 << 20) {
            throw new IllegalStateException("bad RNC sizes");
        }
        int avail = (int) Math.min((long) 18 + plen, src.length - off);
        byte[] d = new byte[avail + 8];
        System.arraycopy(src, off, d, 0, avail);
        Grow out = new Grow(ulen);
        Bits bs = new Bits(d, 18);
        bs.adv(2);
        while (out.n < ulen) {
            int[][] raw = table(bs), dist = table(bs), len = table(bs);
            int cc = bs.read(0xffff, 16);
            while (true) {
                int l = huf(bs, raw);
                if (l != 0) {
                    for (int i = 0, k = Math.max(0, Math.min(l, d.length - bs.p)); i < k; i++) {
                        out.put(d[bs.p + i]);
                    }
                    bs.p += l;
                    bs.fix();
                }
                if (--cc <= 0) {
                    break;
                }
                int pos = huf(bs, dist) + 1;
                l = huf(bs, len) + 2;
                if (pos > out.n) {
                    throw new IllegalStateException("RNC distance out of range");
                }
                for (int i = 0; i < l; i++) {
                    out.put(out.b[out.n - pos]);
                }
            }
        }
        return new Unpacked(java.util.Arrays.copyOf(out.b, ulen), 18 + plen);
    }

    /** Growable output buffer. */
    private static final class Grow {
        byte[] b;
        int n;

        Grow(int size) {
            b = new byte[Math.max(size, 16)];
        }

        void put(int v) {
            if (n == b.length) {
                b = java.util.Arrays.copyOf(b, b.length * 2);
            }
            b[n++] = (byte) v;
        }
    }

    // ------------------------------------------------------------------ method 2

    /** Unpack the RNC method 2 block at {@code off} (MSB-first byte bit stream). */
    public static byte[] unpack2(byte[] src, int off) {
        if (src[off] != 'R' || src[off + 1] != 'N' || src[off + 2] != 'C' || src[off + 3] != 2) {
            throw new IllegalArgumentException("not RNC2");
        }
        int ulen = u32(src, off + 4), plen = u32(src, off + 8);
        byte[] d = new byte[plen + 16];
        System.arraycopy(src, off + 18, d, 0, Math.min(plen, src.length - off - 18));
        M2 s = new M2(d, ulen);
        s.run();
        return java.util.Arrays.copyOf(s.out, s.n);
    }

    private static final class M2 {
        final byte[] d;
        byte[] out;
        int n, p, d7 = 0x80;

        M2(byte[] d, int ulen) {
            this.d = d;
            out = new byte[Math.max(ulen, 16)];
        }

        int gb() {
            int v = d7 << 1;
            int c = (v >> 8) & 1;
            v &= 0xff;
            if (v == 0) {
                int b = d[p++] & 0xff;
                v = ((b << 1) | 1) & 0xff;
                c = b >> 7;
            }
            d7 = v;
            return c;
        }

        int b() {
            return d[p++] & 0xff;
        }

        void put(int v) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, out.length * 2);
            }
            out[n++] = (byte) v;
        }

        void copy(int dist, int len) {
            int s = n - dist - 1;
            for (int i = 0; i < len; i++) {
                put(out[s + i]);
            }
        }

        void run() {
            gb();
            gb();
            while (true) {
                while (gb() == 0) {
                    put(b());
                }
                int d5 = 2, d6;
                if (gb() == 0) {
                    d5 = (d5 << 1) | gb();
                    if (gb() != 0) {
                        d5 -= 1;
                        d5 = (d5 << 1) | gb();
                        if (d5 == 9) {
                            d6 = 0;
                            for (int i = 0; i < 4; i++) {
                                d6 = (d6 << 1) | gb();
                            }
                            int cnt = (d6 + 3) * 4;
                            for (int i = 0; i < cnt; i++) {
                                put(b());
                            }
                            continue;
                        }
                    }
                } else {
                    if (gb() == 0) {
                        copy(b(), d5);
                        continue;
                    }
                    d5 = 3;
                    if (gb() != 0) {
                        d5 = b();
                        if (d5 == 0) {
                            if (gb() != 0) {
                                continue;
                            }
                            break;
                        }
                        d5 += 8;
                    }
                }
                if (gb() == 0) {
                    d6 = b();
                } else {
                    d6 = gb();
                    if (gb() != 0) {
                        d6 = ((d6 << 1) | gb()) | 4;
                        if (gb() == 0) {
                            d6 = (d6 << 1) | gb();
                        }
                    } else if (d6 == 0) {
                        d6 = (1 << 1) | gb();
                    }
                    d6 = ((d6 << 8) & 0xff00) | b();
                }
                copy(d6, d5);
            }
        }
    }
}
