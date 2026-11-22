package bubba.audio;

import bubba.engine.Assets;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Sound effects extracted from disk1.spk / disk2.spk (assets/sfx/diskN_XX.wav,
 * 8-bit mono at the original Paula rate). Errors are ignored so a machine without
 * audio still runs the game.
 */
public final class Sfx {

    private final Assets assets;
    private final Map<String, byte[]> cache = new HashMap<>();

    public Sfx(Assets assets) {
        this.assets = assets;
    }

    /** Play e.g. {@code play("disk1_05")}. */
    public void play(String name) {
        try {
            byte[] wav;
            synchronized (cache) {
                wav = cache.computeIfAbsent(name, n -> assets.bytes("sfx/" + n + ".wav"));
            }
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            Clip clip = AudioSystem.getClip();
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.open(in);
            clip.start();
        } catch (Exception | LinkageError e) {
            // no audio device or missing file: silently ignore
        }
    }
}
