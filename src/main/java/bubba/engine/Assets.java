package bubba.engine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Access to the files extracted by tools/extract_all.py (the {@code assets/} directory).
 * Images are cached; everything is addressed by its path relative to the assets root,
 * e.g. {@code "title/title_base.png"}.
 */
public final class Assets {

    private final Path root;
    private final Map<String, BufferedImage> images = new HashMap<>();

    public Assets(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Assets directory not found: " + root.toAbsolutePath()
                    + " (set -Dbubba.assets=<dir>)");
        }
        this.root = root;
    }

    public static Assets fromSystemProperty() {
        String p = System.getProperty("bubba.assets");
        if (p == null || p.isBlank()) {
            // default when launched from the bubba-java project directory
            Path candidate = Paths.get("..", "assets");
            p = Files.isDirectory(candidate) ? candidate.toString() : "assets";
        }
        return new Assets(Paths.get(p).toAbsolutePath().normalize());
    }

    public Path path(String relative) {
        return root.resolve(relative);
    }

    public boolean exists(String relative) {
        return Files.exists(path(relative));
    }

    /** Load (and cache) an image, converted to ARGB so transparency is kept. */
    /** The image as stored (an indexed PNG keeps its colour indices), not cached. */
    public BufferedImage rawImage(String relative) {
        try {
            BufferedImage src = ImageIO.read(path(relative).toFile());
            if (src == null) {
                throw new IOException("Unreadable image " + relative);
            }
            return src;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load image " + path(relative), e);
        }
    }

    public synchronized BufferedImage image(String relative) {
        return images.computeIfAbsent(relative, r -> {
            try {
                BufferedImage src = ImageIO.read(path(r).toFile());
                if (src == null) {
                    throw new IOException("Unreadable image " + r);
                }
                BufferedImage argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(src, 0, 0, null);
                return argb;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot load image " + path(r), e);
            }
        });
    }

    public byte[] bytes(String relative) {
        try {
            return Files.readAllBytes(path(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path(relative), e);
        }
    }

    public String text(String relative) {
        return new String(bytes(relative), java.nio.charset.StandardCharsets.UTF_8);
    }
}
