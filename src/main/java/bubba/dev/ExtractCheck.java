package bubba.dev;

import bubba.engine.Json;
import bubba.extract.Disks;
import bubba.extract.Extractor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the Java extractor and compares its output with a reference assets directory:
 * PNG by pixels (fully transparent pixels are equal whatever their colour), JSON by value,
 * the rest byte for byte.
 * <pre>ExtractCheck &lt;disk1.zip&gt; &lt;disk2.zip&gt; &lt;out dir&gt; &lt;reference assets&gt;</pre>
 */
public final class ExtractCheck {

    private ExtractCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[2]), ref = Paths.get(args[3]);
        long t = System.nanoTime();
        Disks d = new Disks(Paths.get(args[0]), Paths.get(args[1]));
        Extractor.run(d, out, s -> System.out.println("== " + s));
        System.out.printf("extraction: %d ms%n", (System.nanoTime() - t) / 1_000_000);
        int ok = 0, bad = 0, missing = 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(out)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path f : files) {
            String rel = out.relativize(f).toString().replace('\\', '/');
            if (rel.equals(Extractor.MARKER)) {
                continue;
            }
            Path r = ref.resolve(rel);
            if (!Files.exists(r)) {
                System.out.println("NOT IN REFERENCE " + rel);
                missing++;
                continue;
            }
            String why = compare(f, r, rel);
            if (why == null) {
                ok++;
            } else {
                bad++;
                System.out.println("DIFF " + rel + ": " + why);
            }
        }
        System.out.printf("%d identical, %d different, %d not in reference%n", ok, bad, missing);
    }

    private static String compare(Path a, Path b, String rel) throws Exception {
        if (rel.endsWith(".png")) {
            BufferedImage x = ImageIO.read(a.toFile()), y = ImageIO.read(b.toFile());
            if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
                return "size " + x.getWidth() + "x" + x.getHeight() + " vs " + y.getWidth() + "x" + y.getHeight();
            }
            for (int j = 0; j < x.getHeight(); j++) {
                for (int i = 0; i < x.getWidth(); i++) {
                    int p = x.getRGB(i, j), q = y.getRGB(i, j);
                    if (p != q && !((p >>> 24) == 0 && (q >>> 24) == 0)) {
                        return String.format("pixel %d,%d %08x vs %08x", i, j, p, q);
                    }
                }
            }
            return null;
        }
        byte[] p = Files.readAllBytes(a), q = Files.readAllBytes(b);
        if (rel.endsWith(".json")) {
            Object u = Json.parse(new String(p, StandardCharsets.UTF_8));
            Object v = Json.parse(new String(q, StandardCharsets.UTF_8));
            return u.equals(v) ? null : "value differs";
        }
        if (Arrays.equals(p, q)) {
            return null;
        }
        int i = Arrays.mismatch(p, q);
        return "bytes differ at " + i + " (" + p.length + " vs " + q.length + " bytes)";
    }
}
