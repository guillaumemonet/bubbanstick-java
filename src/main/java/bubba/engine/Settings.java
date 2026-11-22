package bubba.engine;

/** Options chosen in the options screen and used by the game. */
public final class Settings {

    /**
     * In-game sound. The original offers SFX (effects, no music) or MUSIC (music, no effects):
     * L_08276, code 0x6548 / 0x7BD2. BOTH is an addition of this remake: the effects take a
     * Paula channel from the music while they play, as on the Amiga.
     */
    public enum Sound {
        SFX("SFX"), MUSIC("MUSIC"), BOTH("SFX AND MUSIC");

        public final String label;

        Sound(String label) {
            this.label = label;
        }

        public boolean music() {
            return this != SFX;
        }

        public boolean effects() {
            return this != MUSIC;
        }

        public Sound next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static volatile Sound sound = Sound.SFX;

    private Settings() {
    }
}
