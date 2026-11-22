package bubba.emu;

import bubba.audio.ModPlayer;
import bubba.audio.SoundBank;
import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Random;

/**
 * Port of the sound request routine 0x7BC8 / 0x7BCA (d0 = sound number, d1 = period added,
 * d2 = volume added for 0x7BCA, a5 = the object making the sound), playing on the Paula mixer:
 * <ul>
 *   <li>only sounds loaded for the level play (flag table $11152 in the set: -1 = loaded);</li>
 *   <li>volume = table volume + d2; unless the sound is "fixed", it fades with the distance to
 *       Bubba (max(dx,dy) + min/2: beyond 128 pixels, volume - excess/4) and the side chooses the
 *       channels (object more than 8 pixels left: channels 1, 2 first; right: 0, 3);</li>
 *   <li>a channel is taken if free, else from a sound of lower importance (higher priority
 *       number), else from one of the same priority; otherwise the request is dropped;</li>
 *   <li>period = table period + (table mask AND random) + d1 (VBL routine 0x5972).</li>
 * </ul>
 */
public final class GameSounds {

    private final GameMemory ram;
    private final SoundBank bank;
    private final ModPlayer mixer;
    private final Random random = new Random();

    public GameSounds(GameMemory ram, SoundBank bank, ModPlayer mixer) {
        this.ram = ram;
        this.bank = bank;
        this.mixer = mixer;
        // level start (0x7732): the sounds flagged in the set's table get loaded (-1)
        for (int n = 0; n < bank.size(); n++) {
            if (ram.read8(0x11152 + n) != 0) {
                ram.write8(0x11152 + n, 0xff);
            }
        }
    }

    /** Hook body for 0x7BC8 (d2 = 0) and 0x7BCA (d2 from the caller). */
    public void request(M68k c, boolean withVolume) {
        int n = c.d[0] & 0xffff;
        int flag = (byte) ram.read8(0x11152 + n);
        if (flag >= 0) {
            if (flag == 0) {
                ram.write8(0x11152 + n, 1); // asked for, not loaded: remembered for the next level
            }
            return;
        }
        SoundBank.Sound s = bank.get(n);
        if (s == null) {
            return;
        }
        int volume = s.volume() + (withVolume ? (short) c.d[2] : 0);
        int side = 0;
        if (!s.fixed()) {
            int o = c.a[5];
            int dy = Math.abs((short) (ram.read16(o + 6) - ram.read16(0x10916)));
            int dxs = (short) (ram.read16(o + 2) - ram.read16(0x10912));
            int dx = Math.abs(dxs);
            int small = Math.min(dx, dy), big = Math.max(dx, dy);
            int d = 0x80 - (small / 2 + big);
            if (d < 0) {
                volume += d >> 2;
                if (volume <= 0) {
                    return;
                }
            }
            volume = Math.min(volume, 0x40);
            side = dxs <= -8 ? -1 : dxs >= 8 ? 1 : 0;
        }
        int ch = channel(side, s.priority());
        if (ch < 0) {
            return;
        }
        int period = s.period() + (s.periodMask() & random.nextInt(0x10000)) + (short) c.d[1];
        mixer.playEffect(ch, s.data(), period, volume, s.priority());
    }

    /** Channel choice of 0x7C66..0x7D24. */
    private int channel(int side, int prio) {
        int[] order = side < 0 ? new int[]{1, 2, 0, 3} : side > 0 ? new int[]{0, 3, 1, 2} : new int[]{0, 1, 2, 3};
        for (int k : order) {
            if (!mixer.effectActive(k)) {
                return k;
            }
        }
        if (side != 0) {
            int a = order[0], b = order[1];
            if (prio < mixer.effectPriority(a)) {
                return a;
            }
            if (prio <= mixer.effectPriority(b)) {
                return b;
            }
            if (prio == mixer.effectPriority(a)) {
                return a;
            }
        }
        for (int k = 0; k < 4; k++) {
            if (prio < mixer.effectPriority(k)) {
                return k;
            }
        }
        for (int k = 0; k < 4; k++) {
            if (prio == mixer.effectPriority(k)) {
                return k;
            }
        }
        return -1;
    }
}
