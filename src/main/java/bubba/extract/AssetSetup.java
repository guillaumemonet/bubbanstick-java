package bubba.extract;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Finds the assets directory, creating it from the original disks on the first launch.
 * <ol>
 *   <li>{@code -Dbubba.assets=<dir>}: used as is;</li>
 *   <li>{@code ../assets} (development layout): used as is;</li>
 *   <li>otherwise {@code <user data dir>/assets}, extracted from the two disks when missing or
 *       produced by an older extractor. The disks (TOSEC .zip or .adf) are looked for in the
 *       user data dir, next to the application, in the current directory and in Downloads;
 *       if they are not found, the player is asked for them. They are then copied into
 *       {@code <user data dir>/disks} so a later rebuild needs nothing.</li>
 * </ol>
 */
public final class AssetSetup {

    static final String DISK1_URL = "https://amigamuseum.emu-france.info/Fichiers/ADF/B/Bubba%20'n'%20Stix%20(1994)(Core)(Disk%201%20of%202)%5bcr%20LTD%5d.zip";
    static final String DISK2_URL = "https://amigamuseum.emu-france.info/Fichiers/ADF/B/Bubba%20'n'%20Stix%20(1994)(Core)(Disk%202%20of%202)%5bcr%20LTD%5d.zip";

    private AssetSetup() {
    }

    /** The assets directory, extracted if needed; null if the player gave up. */
    public static Path resolve() throws Exception {
        String p = System.getProperty("bubba.assets");
        if (p != null && !p.isBlank()) {
            return Paths.get(p).toAbsolutePath().normalize();
        }
        Path dev = Paths.get("..", "assets");
        if (Files.isDirectory(dev)) {
            return dev.toAbsolutePath().normalize();
        }
        Path data = dataDir();
        Path assets = data.resolve("assets");
        if (Extractor.upToDate(assets)) {
            return assets;
        }
        Path[] disks = findDisks(searchDirs(data));
        while (disks == null) {
            List<Path> chosen = ask();
            if (chosen == null) {
                return null;
            }
            disks = pick(chosen);
            if (disks == null) {
                message("Ces fichiers ne sont pas les deux disquettes de Bubba 'n' Stix.\n"
                        + "Il faut le disque 1 et le disque 2 (archives .zip TOSEC ou images .adf).");
            }
        }
        disks = keep(data.resolve("disks"), disks);
        extract(disks, assets);
        return assets;
    }

    /** Per-user data directory of the game. */
    public static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Paths.get(System.getProperty("user.home"));
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local != null ? Paths.get(local) : home.resolve("AppData/Local")).resolve("BubbaNStix");
        }
        if (os.contains("mac")) {
            return home.resolve("Library/Application Support/BubbaNStix");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank() ? Paths.get(xdg) : home.resolve(".local/share")).resolve("bubbanstix");
    }

    private static List<Path> searchDirs(Path data) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(data.resolve("disks"));
        dirs.add(data);
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            Path a = Paths.get(app).toAbsolutePath().getParent();
            dirs.add(a);
            if (a.getParent() != null) {
                dirs.add(a.getParent());
            }
        }
        dirs.add(Paths.get("").toAbsolutePath());
        dirs.add(Paths.get("..").toAbsolutePath().normalize());
        dirs.add(Paths.get(System.getProperty("user.home"), "Downloads"));
        return dirs;
    }

    /** Disk 1 and disk 2 among the .zip / .adf files of the given directories, or null. */
    static Path[] findDisks(List<Path> dirs) {
        List<Path> candidates = new ArrayList<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> s = Files.newDirectoryStream(d, "*.{zip,adf,ZIP,ADF}")) {
                for (Path f : s) {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".adf") || n.contains("bubba")) {
                        candidates.add(f);
                    }
                }
            } catch (IOException e) {
                // unreadable directory: skipped
            }
        }
        return pick(candidates);
    }

    /** Identify the two disks by their content. */
    static Path[] pick(List<Path> files) {
        Path[] disks = new Path[2];
        for (Path f : files) {
            try {
                if (Files.size(f) > 4 << 20) {
                    continue;
                }
                int n = Disks.diskNumber(Disks.adf(f));
                if (n > 0 && disks[n - 1] == null) {
                    disks[n - 1] = f;
                }
            } catch (IOException | RuntimeException e) {
                // not a disk of the game
            }
        }
        return disks[0] != null && disks[1] != null ? disks : null;
    }

    /** Copy the disks into {@code dir} (unless they are already there). */
    private static Path[] keep(Path dir, Path[] disks) {
        Path[] out = disks.clone();
        try {
            Files.createDirectories(dir);
            for (int i = 0; i < 2; i++) {
                if (!disks[i].toAbsolutePath().getParent().equals(dir.toAbsolutePath())) {
                    out[i] = dir.resolve(disks[i].getFileName());
                    Files.copy(disks[i], out[i], StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            return disks; // works without the copy
        }
        return out;
    }

    // ------------------------------------------------------------------ extraction

    private static void extract(Path[] disks, Path assets) throws Exception {
        Path tmp = assets.resolveSibling("assets.tmp");
        delete(tmp);
        if (GraphicsEnvironment.isHeadless()) {
            Extractor.run(new Disks(disks[0], disks[1]), tmp, s -> System.out.println("Extraction : " + s));
        } else {
            withProgress(disks, tmp);
        }
        delete(assets);
        Files.move(tmp, assets);
    }

    private static void withProgress(Path[] disks, Path tmp) throws Exception {
        JDialog dlg = new JDialog((java.awt.Frame) null, "Bubba 'n' Stix", false);
        JLabel label = new JLabel("Lecture des disquettes...");
        JProgressBar bar = new JProgressBar(0, 13);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("Préparation des données du jeu (une seule fois)"), BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        panel.add(label, BorderLayout.SOUTH);
        SwingUtilities.invokeAndWait(() -> {
            dlg.setContentPane(panel);
            dlg.setSize(380, 130);
            dlg.setLocationRelativeTo(null);
            dlg.setVisible(true);
        });
        try {
            Disks d = new Disks(disks[0], disks[1]);
            Extractor.run(d, tmp, s -> SwingUtilities.invokeLater(() -> {
                label.setText(s);
                bar.setValue(bar.getValue() + 1);
            }));
        } finally {
            SwingUtilities.invokeLater(dlg::dispose);
        }
    }

    private static void delete(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    private static void message(String text) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(text);
            return;
        }
        SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, text, "Bubba 'n' Stix",
                JOptionPane.WARNING_MESSAGE));
    }

    /** Ask the player for the disk files; null if cancelled. */
    private static List<Path> ask() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Disquettes de Bubba 'n' Stix introuvables. Placez les deux .zip TOSEC dans "
                    + dataDir().resolve("disks") + "\n  " + DISK1_URL + "\n  " + DISK2_URL);
            return null;
        }
        List<Path> result = new ArrayList<>();
        boolean[] chosen = {false};
        SwingUtilities.invokeAndWait(() -> {
            JDialog dlg = new JDialog((java.awt.Frame) null, "Bubba 'n' Stix", true);
            JEditorPane text = new JEditorPane("text/html", "<html><body style='font-family:sans-serif;width:420px'>"
                    + "<h3>Disquettes du jeu original</h3>"
                    + "<p>Ce remake utilise les données du jeu Amiga original (Core Design, 1994), "
                    + "qui ne sont pas fournies. Il faut les deux disquettes, au format TOSEC :</p><ul>"
                    + "<li><a href=\"" + DISK1_URL + "\">Bubba 'n' Stix (1994)(Core)(Disk 1 of 2)[cr LTD].zip</a></li>"
                    + "<li><a href=\"" + DISK2_URL + "\">Bubba 'n' Stix (1994)(Core)(Disk 2 of 2)[cr LTD].zip</a></li>"
                    + "</ul><p>Le bouton <b>Télécharger</b> les récupère directement sur Amiga Museum ; "
                    + "sinon, téléchargez-les puis indiquez-les ci-dessous (fichiers .zip ou .adf). "
                    + "Elles ne sont lues qu'une fois : les données sont ensuite gardées dans<br><code>"
                    + dataDir() + "</code></p></body></html>");
            text.setEditable(false);
            text.setOpaque(false);
            text.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && Desktop.isDesktopSupported()) {
                    try {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    } catch (Exception ex) {
                        // no browser: the address stays visible
                    }
                }
            });
            JButton choose = new JButton("Choisir les deux fichiers...");
            JButton quit = new JButton("Quitter");
            choose.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(Paths.get(System.getProperty("user.home"), "Downloads").toFile());
                fc.setMultiSelectionEnabled(true);
                fc.setFileFilter(new FileNameExtensionFilter("Disquettes Amiga (.zip, .adf)", "zip", "adf"));
                if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                    for (File f : fc.getSelectedFiles()) {
                        result.add(f.toPath());
                    }
                    chosen[0] = true;
                    dlg.dispose();
                }
            });
            JButton download = new JButton("Télécharger depuis Amiga Museum");
            download.addActionListener(e -> {
                download.setEnabled(false);
                choose.setEnabled(false);
                download.setText("Téléchargement...");
                new Thread(() -> {
                    try {
                        List<Path> got = download(dataDir().resolve("disks"));
                        SwingUtilities.invokeLater(() -> {
                            result.addAll(got);
                            chosen[0] = true;
                            dlg.dispose();
                        });
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(dlg, "Le téléchargement a échoué :\n" + ex.getMessage()
                                    + "\n\nTéléchargez les deux fichiers avec les liens, puis choisissez-les.",
                                    "Bubba 'n' Stix", JOptionPane.WARNING_MESSAGE);
                            download.setText("Télécharger depuis Amiga Museum");
                            download.setEnabled(true);
                            choose.setEnabled(true);
                        });
                    }
                }, "disk-download").start();
            });
            quit.addActionListener(e -> dlg.dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(quit);
            buttons.add(choose);
            buttons.add(download);
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(text);
            panel.add(buttons);
            dlg.setContentPane(panel);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
            dlg.setVisible(true);
        });
        return chosen[0] ? result : null;
    }

    /** Downloads the two TOSEC archives from Amiga Museum into {@code dir}. */
    static List<Path> download(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20)).build();
        List<Path> out = new ArrayList<>();
        int n = 1;
        for (String url : new String[]{DISK1_URL, DISK2_URL}) {
            Path f = dir.resolve("Bubba 'n' Stix (1994)(Core)(Disk " + n++ + " of 2)[cr LTD].zip");
            java.net.http.HttpResponse<Path> r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .header("User-Agent", "BubbaNStix-remake").timeout(java.time.Duration.ofMinutes(2)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofFile(f));
            if (r.statusCode() != 200) {
                Files.deleteIfExists(f);
                throw new IOException("HTTP " + r.statusCode() + " pour " + url);
            }
            out.add(f);
        }
        return out;
    }
}
