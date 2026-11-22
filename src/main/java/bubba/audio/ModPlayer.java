package bubba.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Minimal ProTracker (M.K., 4 channels, 31 samples) replayer.
 * <p>
 * The game's NoisePacker modules are converted to standard MOD files by
 * tools/np2mod.py; this class plays them with the PAL Paula clock, Amiga hard stereo
 * (channels 1+4 left, 2+3 right, with a little cross-feed) and the effects the game uses:
 * 0 arpeggio, 1/2 porta, 3 tone porta, 4 vibrato, 5, 6, 9 offset, A volume slide,
 * B jump, C volume, D break, E1/E2/E9/EA/EB/EC/ED/EE, F speed/tempo.
 */
public final class ModPlayer {

    private static final int RATE = 44100;
    private static final double PAULA = 3546895.0;
    private static final int[] PERIODS = {
        856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
        428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
        214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113};
    private static final int[] SINE = {
        0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253,
        255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24};

    private static final class Sample {
        byte[] data = new byte[0];
        int length;      // bytes
        int loopStart;   // bytes
        int loopLength;  // bytes (<= 2 means no loop)
        int volume;
        int finetune;
    }

    private static final class Channel {
        Sample sample;
        double pos;
        boolean active;
        int period, volume;
        int note = -1;
        int effect, param;
        int portaTarget, portaSpeed;
        int vibSpeed, vibDepth, vibPos;
        int outPeriod;
        int loopRow, loopCount;
        int delayedSample, delayedPeriod;
    }

    /** A sound effect holding a Paula channel (the music's notes on it are not heard meanwhile). */
    private static final class Fx {
        byte[] data;
        double pos;
        int period, volume, priority;
        boolean active;
    }

    private final Fx[] fx = {new Fx(), new Fx(), new Fx(), new Fx()};
    private final Object lock = new Object();
    private Sample[] samples;
    private byte[] mod;
    private int songLength;
    private int[] order;
    private int patternBase;
    private final Channel[] ch = new Channel[4];

    private int speed, tempo, tick, row, pos;
    private int nextRow, nextPos;
    private boolean jump;
    private int patternDelay;
    private double samplesPerTick, tickRemain;
    private float masterVolume = 0.8f;

    private Thread thread;
    private volatile boolean playing;
    private SourceDataLine line;

    /** Load and start playing a MOD file (loops forever). */
    public void play(byte[] modData) {
        stop();
        synchronized (lock) {
            parse(modData);
            reset();
        }
        playing = true;
        thread = new Thread(this::run, "mod-player");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Play a sound effect on Paula channel {@code channel} (0-3; 0 and 3 left, 1 and 2 right):
     * signed 8-bit {@code data}, Paula {@code period}, {@code volume} 0-64. Starts the mixer if
     * no music is playing.
     */
    public void playEffect(int channel, byte[] data, int period, int volume, int priority) {
        synchronized (lock) {
            Fx f = fx[channel];
            f.data = data;
            f.pos = 0;
            f.period = Math.max(64, period);
            f.volume = Math.max(0, Math.min(64, volume));
            f.priority = priority;
            f.active = true;
        }
        if (!playing) {
            playing = true;
            thread = new Thread(this::run, "mod-player");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** True while an effect plays on {@code channel}. */
    public boolean effectActive(int channel) {
        synchronized (lock) {
            return fx[channel].active;
        }
    }

    public int effectPriority(int channel) {
        synchronized (lock) {
            return fx[channel].priority;
        }
    }

    /** Stop the music (effects go on). */
    public void stopMusic() {
        synchronized (lock) {
            mod = null;
            samples = null;
        }
    }

    /** Stop everything: music (forgotten, a later effect must not restart it) and effects. */
    public void stop() {
        synchronized (lock) {
            for (Fx f : fx) {
                f.active = false;
            }
            mod = null;
            samples = null;
        }
        playing = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public void setVolume(float v) {
        masterVolume = Math.max(0f, Math.min(1f, v));
    }

    public boolean isPlaying() {
        return playing;
    }

    // ------------------------------------------------------------------ loading

    private void parse(byte[] m) {
        if (m.length < 1084 || m[1080] != 'M' || m[1081] != '.' || m[1082] != 'K' || m[1083] != '.') {
            throw new IllegalArgumentException("Not a 4-channel M.K. module");
        }
        mod = m;
        samples = new Sample[32];
        samples[0] = new Sample();
        int off = 20;
        for (int i = 1; i <= 31; i++, off += 30) {
            Sample s = new Sample();
            s.length = u16(m, off + 22) * 2;
            s.finetune = m[off + 24] & 15;
            if (s.finetune > 7) {
                s.finetune -= 16;
            }
            s.volume = Math.min(64, m[off + 25] & 0xff);
            s.loopStart = u16(m, off + 26) * 2;
            s.loopLength = u16(m, off + 28) * 2;
            samples[i] = s;
        }
        songLength = m[950] & 0xff;
        order = new int[128];
        int maxPat = 0;
        for (int i = 0; i < 128; i++) {
            order[i] = m[952 + i] & 0xff;
            maxPat = Math.max(maxPat, order[i]);
        }
        patternBase = 1084;
        int sp = patternBase + (maxPat + 1) * 1024;
        for (int i = 1; i <= 31; i++) {
            Sample s = samples[i];
            int len = Math.max(0, Math.min(s.length, m.length - sp));
            s.data = new byte[len];
            System.arraycopy(m, Math.min(sp, m.length), s.data, 0, len);
            s.length = len;
            if (s.loopStart + s.loopLength > len) {
                s.loopLength = Math.max(0, len - s.loopStart);
            }
            sp += u16(m, 20 + (i - 1) * 30 + 22) * 2;
        }
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private void reset() {
        for (int i = 0; i < 4; i++) {
            ch[i] = new Channel();
        }
        speed = 6;
        setTempo(125);
        tick = 0;
        row = 0;
        pos = 0;
        jump = false;
        patternDelay = 0;
        tickRemain = 0;
    }

    private void setTempo(int bpm) {
        tempo = bpm;
        samplesPerTick = RATE * 2.5 / bpm;
    }

    // ------------------------------------------------------------------ audio thread

    private void run() {
        AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
        try {
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, RATE / 10 * 4);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.err.println("Music disabled: " + e.getMessage());
            playing = false;
            return;
        }
        int frames = 1024;
        byte[] out = new byte[frames * 4];
        float[] mix = new float[frames * 2];
        while (playing) {
            synchronized (lock) {
                render(mix, frames);
            }
            for (int i = 0; i < frames * 2; i++) {
                int v = (int) (mix[i] * 32767f * masterVolume);
                v = Math.max(-32768, Math.min(32767, v));
                out[i * 2] = (byte) v;
                out[i * 2 + 1] = (byte) (v >> 8);
            }
            line.write(out, 0, out.length);
        }
        line.drain();
        line.close();
    }

    private void render(float[] mix, int frames) {
        java.util.Arrays.fill(mix, 0f);
        int done = 0;
        while (done < frames) {
            if (mod == null) {
                for (int c = 0; c < 4; c++) {
                    mixFx(fx[c], mix, done, frames - done, c == 0 || c == 3);
                }
                return;
            }
            if (tickRemain <= 0) {
                processTick();
                tickRemain += samplesPerTick;
            }
            int n = (int) Math.min(frames - done, Math.ceil(tickRemain));
            for (int c = 0; c < 4; c++) {
                if (fx[c].active) {
                    mixFx(fx[c], mix, done, n, c == 0 || c == 3);
                } else {
                    mixChannel(ch[c], mix, done, n, c == 0 || c == 3);
                }
            }
            done += n;
            tickRemain -= n;
        }
    }

    private void mixFx(Fx f, float[] mix, int start, int n, boolean left) {
        if (!f.active) {
            return;
        }
        double step = PAULA / f.period / RATE;
        float vol = f.volume / 64f / 128f * 0.5f;
        float main = vol * 0.8f, cross = vol * 0.2f;
        for (int i = 0; i < n; i++) {
            int p = (int) f.pos;
            if (p >= f.data.length) {
                f.active = false;
                return;
            }
            float v = f.data[p];
            int o = (start + i) * 2;
            mix[o] += v * (left ? main : cross);
            mix[o + 1] += v * (left ? cross : main);
            f.pos += step;
        }
    }

    private void mixChannel(Channel c, float[] mix, int start, int n, boolean left) {
        if (!c.active || c.sample == null || c.outPeriod < 1) {
            return;
        }
        Sample s = c.sample;
        byte[] d = s.data;
        double step = PAULA / c.outPeriod / RATE;
        float vol = c.volume / 64f / 128f * 0.5f;
        float main = vol * 0.8f;
        float cross = vol * 0.2f;
        boolean loops = s.loopLength > 2;
        int end = loops ? s.loopStart + s.loopLength : s.length;
        for (int i = 0; i < n; i++) {
            int p = (int) c.pos;
            if (p >= end) {
                if (loops) {
                    c.pos = s.loopStart + (c.pos - end) % s.loopLength;
                    p = (int) c.pos;
                } else {
                    c.active = false;
                    return;
                }
            }
            if (p >= d.length) {
                c.active = false;
                return;
            }
            float v = d[p];
            int o = (start + i) * 2;
            if (left) {
                mix[o] += v * main;
                mix[o + 1] += v * cross;
            } else {
                mix[o] += v * cross;
                mix[o + 1] += v * main;
            }
            c.pos += step;
        }
    }

    // ------------------------------------------------------------------ replay logic

    private void processTick() {
        if (tick == 0) {
            if (patternDelay > 0) {
                patternDelay--;
            } else {
                readRow();
            }
        } else {
            for (Channel c : ch) {
                effectTick(c);
            }
        }
        tick++;
        if (tick >= speed) {
            tick = 0;
            if (patternDelay > 0) {
                return;
            }
            if (jump) {
                pos = nextPos;
                row = nextRow;
                jump = false;
            } else {
                row++;
                if (row >= 64) {
                    row = 0;
                    pos++;
                }
            }
            if (pos >= songLength) {
                pos = 0;
            }
        }
    }

    private void readRow() {
        int pat = order[pos];
        int base = patternBase + pat * 1024 + row * 16;
        for (int i = 0; i < 4; i++) {
            Channel c = ch[i];
            int o = base + i * 4;
            int b0 = mod[o] & 0xff, b1 = mod[o + 1] & 0xff, b2 = mod[o + 2] & 0xff, b3 = mod[o + 3] & 0xff;
            int smp = (b0 & 0xf0) | (b2 >> 4);
            int period = ((b0 & 0x0f) << 8) | b1;
            c.effect = b2 & 0x0f;
            c.param = b3;
            if (c.effect == 0xE && (c.param >> 4) == 0xD && (c.param & 15) > 0) {
                c.delayedSample = smp;
                c.delayedPeriod = period;
                c.outPeriod = c.period;
                continue;
            }
            trigger(c, smp, period);
            effectRow(c);
        }
    }

    private void trigger(Channel c, int smp, int period) {
        if (smp > 0 && smp <= 31) {
            c.sample = samples[smp];
            c.volume = c.sample.volume;
        }
        if (period > 0) {
            int p = finetuned(period, c.sample);
            if (c.effect == 3 || c.effect == 5) {
                c.portaTarget = p;
            } else {
                c.period = p;
                c.pos = 0;
                c.active = c.sample != null && c.sample.length > 0;
                c.vibPos = 0;
                if (c.effect == 9) {
                    c.pos = (c.param == 0 ? 0 : c.param) * 256;
                }
            }
        }
        c.outPeriod = c.period;
    }

    private static int finetuned(int period, Sample s) {
        if (s == null || s.finetune == 0) {
            return period;
        }
        return (int) Math.round(period * Math.pow(2, -s.finetune / (12.0 * 8.0)));
    }

    private void effectRow(Channel c) {
        int p = c.param, x = p >> 4, y = p & 15;
        switch (c.effect) {
            case 0x3 -> {
                if (p != 0) {
                    c.portaSpeed = p;
                }
            }
            case 0x4 -> {
                if (x != 0) {
                    c.vibSpeed = x;
                }
                if (y != 0) {
                    c.vibDepth = y;
                }
            }
            case 0xB -> {
                nextPos = p;
                nextRow = 0;
                jump = true;
            }
            case 0xC -> c.volume = Math.min(64, p);
            case 0xD -> {
                if (!jump) {
                    nextPos = pos + 1;
                }
                nextRow = Math.min(63, x * 10 + y);
                jump = true;
            }
            case 0xE -> {
                switch (x) {
                    case 0x1 -> c.period = Math.max(113, c.period - y);
                    case 0x2 -> c.period = Math.min(856, c.period + y);
                    case 0x6 -> {
                        if (y == 0) {
                            c.loopRow = row;
                        } else {
                            if (c.loopCount == 0) {
                                c.loopCount = y;
                            } else {
                                c.loopCount--;
                            }
                            if (c.loopCount > 0) {
                                nextRow = c.loopRow;
                                nextPos = pos;
                                jump = true;
                            }
                        }
                    }
                    case 0xA -> c.volume = Math.min(64, c.volume + y);
                    case 0xB -> c.volume = Math.max(0, c.volume - y);
                    case 0xC -> {
                        if (y == 0) {
                            c.volume = 0;
                        }
                    }
                    case 0xE -> patternDelay = y;
                    default -> { }
                }
            }
            case 0xF -> {
                if (p == 0) {
                    break;
                }
                if (p < 32) {
                    speed = p;
                } else {
                    setTempo(p);
                }
            }
            default -> { }
        }
        c.outPeriod = c.period;
    }

    private void effectTick(Channel c) {
        int p = c.param, x = p >> 4, y = p & 15;
        c.outPeriod = c.period;
        switch (c.effect) {
            case 0x0 -> {
                if (p != 0) {
                    int k = tick % 3;
                    c.outPeriod = k == 0 ? c.period : shift(c.period, k == 1 ? x : y);
                }
            }
            case 0x1 -> {
                c.period = Math.max(113, c.period - p);
                c.outPeriod = c.period;
            }
            case 0x2 -> {
                c.period = Math.min(856, c.period + p);
                c.outPeriod = c.period;
            }
            case 0x3 -> tonePorta(c);
            case 0x4 -> vibrato(c);
            case 0x5 -> {
                tonePorta(c);
                volSlide(c, x, y);
            }
            case 0x6 -> {
                vibrato(c);
                volSlide(c, x, y);
            }
            case 0xA -> volSlide(c, x, y);
            case 0xE -> {
                if (x == 0x9 && y > 0 && tick % y == 0) {
                    c.pos = 0;
                    c.active = c.sample != null;
                } else if (x == 0xC && tick == y) {
                    c.volume = 0;
                } else if (x == 0xD && tick == y) {
                    c.effect = 0;
                    trigger(c, c.delayedSample, c.delayedPeriod);
                }
            }
            default -> { }
        }
    }

    private void tonePorta(Channel c) {
        if (c.portaTarget == 0) {
            return;
        }
        if (c.period < c.portaTarget) {
            c.period = Math.min(c.portaTarget, c.period + c.portaSpeed);
        } else if (c.period > c.portaTarget) {
            c.period = Math.max(c.portaTarget, c.period - c.portaSpeed);
        }
        c.outPeriod = c.period;
    }

    private void vibrato(Channel c) {
        int delta = SINE[c.vibPos & 31] * c.vibDepth / 128;
        if ((c.vibPos & 32) != 0) {
            delta = -delta;
        }
        c.outPeriod = c.period + delta;
        c.vibPos = (c.vibPos + c.vibSpeed) & 63;
    }

    private static void volSlide(Channel c, int up, int down) {
        if (up > 0) {
            c.volume = Math.min(64, c.volume + up);
        } else {
            c.volume = Math.max(0, c.volume - down);
        }
    }

    private static int shift(int period, int semis) {
        int best = 0;
        for (int i = 1; i < PERIODS.length; i++) {
            if (Math.abs(PERIODS[i] - period) < Math.abs(PERIODS[best] - period)) {
                best = i;
            }
        }
        return PERIODS[Math.min(PERIODS.length - 1, best + semis)];
    }
}
