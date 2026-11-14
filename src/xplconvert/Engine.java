package xplconvert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs a conversion over every gathered preset and reports line by line.  Shared by the CLI
 * (prints to stdout) and the GUI (appends to the log area).
 */
public final class Engine {
    /** Receives report lines, one per call. */
    public interface Sink {
        void line(String line);
    }

    private Engine() {
    }

    public static int run(Options o, Sink sink) {
        final List<Path> files;
        try {
            files = Converter.gather(o.paths);
        } catch (ConversionException e) {
            sink.line(e.getMessage());
            return 1;
        }
        if (files.isEmpty()) {
            sink.line("no .xpl files found");
            return 1;
        }

        String suffix = (o.suffix == null || o.suffix.isEmpty())
                ? ("sdk".equals(o.target) ? "-sdk" : "-orig")
                : o.suffix;

        sink.line(String.format("%-44s %-12s %s", "file", "state", "path"));
        sink.line(repeat('-', 108));
        int ok = 0;
        int failed = 0;
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (Path path : files) {
            String name = path.getFileName().toString();
            final Converter.Outcome out;
            try {
                out = Converter.convertOne(path, o);
            } catch (ConversionException e) {
                failed++;
                sink.line(String.format("%-44s FAILED: %s", clip(name), e.getMessage()));
                continue;
            }

            Path dest;
            if (o.out != null && !o.out.isEmpty()) {
                dest = Paths.get(o.out).resolve(name);
            } else {
                int dot = name.lastIndexOf('.');
                String stem = dot >= 0 ? name.substring(0, dot) : name;
                String ext = dot >= 0 ? name.substring(dot) : "";
                dest = path.resolveSibling(stem + suffix + ext);
            }
            // two presets with the same file name in different folders would overwrite each
            // other in a flat output folder -- refuse instead of losing one
            if (!claimed.add(dest.toAbsolutePath().normalize().toString())) {
                failed++;
                sink.line(String.format("%-44s FAILED: %s", clip(name),
                        "output name already used by another preset in this run"));
                continue;
            }
            if (!o.dryRun) {
                try {
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.write(dest, out.text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    failed++;
                    sink.line(String.format("%-44s FAILED: %s", clip(name), e.getMessage()));
                    continue;
                }
            }
            ok++;
            sink.line(String.format("%-44s %-12s %s", clip(name), out.format, out.newPath));
            sink.line(String.format("%-44s %-12s %s", "", "",
                    out.note + (out.moved ? "" : "  (path unchanged)")));
        }
        sink.line(repeat('-', 108));
        sink.line(String.format("%d converted, %d failed%s", ok, failed,
                o.dryRun ? "  (dry run, nothing written)" : ""));
        return failed == 0 ? 0 : 1;
    }

    private static String clip(String s) {
        return s.length() > 44 ? s.substring(0, 44) : s;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
