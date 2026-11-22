package bubba.audio;

import bubba.engine.Assets;
import bubba.engine.Json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sound effects of one disk (assets/sfx/sfx.json + diskN_XX.wav): sound number n of a level =
 * entry n of its disk's SPAK bank (the game's sound table, 24 bytes per sound, code 0x7BC8).
 */
public final class SoundBank {

    /** One sound: signed 8-bit samples and the fields of its table entry. */
    public record Sound(byte[] data, int period, int periodMask, int volume, int priority, boolean fixed) {
    }

    private final Map<Integer, Sound> sounds = new HashMap<>();

    /** {@code bank}: "disk1" or "disk2". */
    public SoundBank(Assets assets, String bank) {
        Map<String, byte[]> files = new HashMap<>();
        List<Object> list = Json.array(Json.parse(assets.text("sfx/sfx.json")));
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            if (!bank.equals(m.get("bank"))) {
                continue;
            }
            String file = (String) m.get("file");
            byte[] data = files.computeIfAbsent(file, f -> pcm(assets.bytes("sfx/" + f)));
            sounds.put(Json.integer(m.get("index")), new Sound(data, Json.integer(m.get("period")),
                    Json.integer(m.get("period_mask")), Json.integer(m.get("volume")),
                    Json.integer(m.get("priority")), Json.integer(m.get("fixed")) != 0));
        }
    }

    public Sound get(int n) {
        return sounds.get(n);
    }

    public int size() {
        return sounds.size();
    }

    /** 8-bit unsigned mono WAV -> signed samples (data chunk). */
    private static byte[] pcm(byte[] wav) {
        int p = 12;
        while (p + 8 <= wav.length) {
            int len = (wav[p + 4] & 0xff) | (wav[p + 5] & 0xff) << 8 | (wav[p + 6] & 0xff) << 16 | (wav[p + 7] & 0xff) << 24;
            if (wav[p] == 'd' && wav[p + 1] == 'a' && wav[p + 2] == 't' && wav[p + 3] == 'a') {
                byte[] out = new byte[Math.min(len, wav.length - p - 8)];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) ((wav[p + 8 + i] & 0xff) - 128);
                }
                return out;
            }
            p += 8 + len + (len & 1);
        }
        return new byte[0];
    }
}
