package xplconvert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts one preset: rewrites the {@code <PLUGIN file>} path for the target layout and
 * rewrites the state itself.  Nothing is hardcoded about any particular plugin -- folder and
 * .so names are read from the preset and rebuilt for the target.
 */
public final class Converter {
    private static final Pattern PLUGIN = Pattern.compile("<PLUGIN\\b[^>]*?/>", Pattern.DOTALL);
    private static final Pattern FILE_ATTR = Pattern.compile("\\bfile=\"([^\"]*)\"");
    private static final Pattern UID_ATTR = Pattern.compile("\\buid=\"([0-9A-Fa-fx]+)\"");
    private static final Pattern STATE64 = Pattern.compile("<state64>(.*?)</state64>", Pattern.DOTALL);
    private static final Pattern STATE = Pattern.compile("<state>(.*?)</state>", Pattern.DOTALL);
    // removal keeps the file's own shape: the indent of the state line and the newline after
    // it, so a converted file is laid out exactly like one the MPC wrote
    private static final Pattern STATE64_REMOVE =
            Pattern.compile("[ \\t]*<state64>.*?</state64>\\s*", Pattern.DOTALL);
    private static final Pattern STATE_REMOVE =
            Pattern.compile("[ \\t]*<state>.*?</state>\\s*", Pattern.DOTALL);

    private Converter() {
    }

    /** Result of converting one preset. */
    public static final class Outcome {
        public final String text;
        public final String format;
        public final String note;
        public final String oldPath;
        public final String newPath;
        public final boolean moved;

        Outcome(String text, String format, String note, String oldPath, String newPath, boolean moved) {
            this.text = text;
            this.format = format;
            this.note = note;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.moved = moved;
        }
    }

    private static final class State {
        final String kind;
        final String body;

        State(String kind, String body) {
            this.kind = kind;
            this.body = body;
        }
    }

    private static final class Retarget {
        final String text;
        final String oldPath;
        final String newPath;
        final boolean moved;

        Retarget(String text, String oldPath, String newPath, boolean moved) {
            this.text = text;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.moved = moved;
        }
    }

    /** Read a preset and produce its converted form for the options given. */
    public static Outcome convertOne(Path file, Options o) {
        final String text;
        try {
            // UTF-8 with replacement characters, like Python's errors="replace"
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConversionException("cannot read: " + e.getMessage(), e);
        }

        State st = findState(text);
        boolean juce = "juce".equals(st.kind);
        byte[] blob = Codec.decode(st.body, juce);

        final byte[] headerBlob;
        final Codec.ChunkLoc loc;
        if (Codec.isMpcp(blob)) {                    // already an SDK chunk: no wrapper to locate
            loc = new Codec.ChunkLoc(0, blob.length, -1);
            headerBlob = null;
        } else {
            Codec.ChunkLoc found = Codec.findChunk(blob);
            if (found == null) {
                // plugin-specific chunk this tool cannot parse (JSON, custom text, binary...):
                // convert the path only and leave the state byte-for-byte alone
                Retarget r = retarget(text, o);
                return new Outcome(r.text, "opaque",
                        "state kept as-is (" + describeOpaque(blob) + ")",
                        r.oldPath, r.newPath, r.moved);
            }
            loc = found;
            headerBlob = found.start > 0 ? Arrays.copyOfRange(blob, 0, found.start) : null;
        }
        byte[] chunk = Arrays.copyOfRange(blob, loc.start, loc.start + loc.length);

        final String fmt;
        Codec.Mpcp mpcp = null;
        Codec.ParsedText parsed = null;
        if (Codec.isMpcp(chunk)) {
            fmt = "MPCP";
            mpcp = Codec.parseMpcp(chunk);
        } else {
            parsed = Codec.parseTextChunk(chunk);
            fmt = "text(" + parsed.header + ")";
        }

        final byte[] payload;
        final String note;
        final String newElem;
        final String newEnc;
        if ("sdk".equals(o.target)) {
            if ("MPCP".equals(fmt)) {
                payload = chunk;
                note = "state already MPCP";
            } else {
                // extra keys leave the parameter list (the SDK loader would ignore them as
                // unknown ids) and go into the chunk's extra section instead
                Set<String> extraKeys = o.extraKeys;
                LinkedHashMap<String, String> extraKv = new LinkedHashMap<>();
                List<String> order = new ArrayList<>();
                for (Map.Entry<String, String> e : parsed.kv.entrySet()) {
                    if (extraKeys.contains(e.getKey())) {
                        extraKv.put(e.getKey(), e.getValue());
                    } else {
                        order.add(e.getKey());
                    }
                }
                StringBuilder ex = new StringBuilder();
                for (Map.Entry<String, String> e : extraKv.entrySet()) {
                    if (ex.length() > 0) {
                        ex.append('\t');
                    }
                    ex.append(e.getKey()).append('=').append(e.getValue());
                }
                byte[] extra = ex.toString().getBytes(StandardCharsets.UTF_8);
                List<Codec.MpcpEntry> entries = new ArrayList<>(order.size());
                for (String k : order) {
                    entries.add(new Codec.MpcpEntry(k, Codec.num(parsed.kv.get(k))));
                }
                payload = Codec.buildMpcp(entries, extra);
                note = String.format("text -> MPCP: %d params, %d extra bytes", order.size(), extra.length);
            }
            newElem = "state64";
            newEnc = "std";
        } else {
            final byte[] classic;
            if (!"MPCP".equals(fmt)) {
                classic = chunk;
                note = "state already classic";
            } else {
                LinkedHashMap<String, String> merged = Codec.splitExtra(mpcp.extra);
                List<String> order = new ArrayList<>(mpcp.entries.size());
                for (Codec.MpcpEntry e : mpcp.entries) {
                    order.add(e.id);
                    merged.put(e.id, Codec.txt(e.plain));
                }
                classic = Codec.buildTextChunk("3", merged, order);
                note = String.format("MPCP -> text: %d params, %d extra merged",
                        mpcp.entries.size(), Codec.splitExtra(mpcp.extra).size());
            }
            // the classic layout stores an FxBk-wrapped chunk, exactly as the MPC writes it
            Matcher um = UID_ATTR.matcher(text);
            String uid = um.find() ? um.group(1) : "";
            payload = concat(Codec.fxbkHeader(uid, classic.length, headerBlob, fxVersionOf(text)),
                    classic);
            newElem = "state";
            newEnc = "juce";
        }

        Retarget r = retarget(text, o);
        String out = r.text;
        String encBody = Codec.encode(payload, "juce".equals(newEnc));
        out = STATE64_REMOVE.matcher(out).replaceAll("");
        out = STATE_REMOVE.matcher(out).replaceAll("");
        // keep the preset's own line endings: the MPC writes CRLF, and a converted file
        // should be shaped exactly like its neighbours
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String pair = "  <" + newElem + ">" + encBody + "</" + newElem + ">";
        if (out.contains("</preset>")) {
            // every preset the MPC writes keeps the state as a direct child of <pluginstate>,
            // right after </preset> -- put it back exactly there
            out = out.replaceFirst(Pattern.quote("</preset>"),
                    Matcher.quoteReplacement("</preset>" + eol + pair));
        } else if (out.contains("</pluginstate>")) {
            out = out.replaceFirst(Pattern.quote("</pluginstate>"),
                    Matcher.quoteReplacement(pair + eol + "</pluginstate>"));
        }
        return new Outcome(out, fmt, note, r.oldPath, r.newPath, r.moved);
    }

    private static State findState(String text) {
        Matcher m64 = STATE64.matcher(text);
        if (m64.find()) {
            String body = m64.group(1).trim();
            return new State(body.contains(".") ? "juce" : "std", body);
        }
        Matcher m = STATE.matcher(text);
        if (m.find()) {
            String body = m.group(1).trim();
            return new State(body.contains(".") ? "juce" : "std", body);
        }
        throw new ConversionException("no <state> or <state64>");
    }

    /** Best-effort label for an unrecognized payload, for the report line. */
    private static String describeOpaque(byte[] blob) {
        int start = 0;
        if (blob.length >= 160 && blob[0] == 'C' && blob[1] == 'c' && blob[2] == 'n' && blob[3] == 'K'
                && beAt(blob, 156) == blob.length - 160) {
            start = 160;                             // FxBk: payload after the wrapper
        } else if (blob.length >= 48 && blob[0] == 'F' && blob[1] == 'x' && blob[2] == 'C' && blob[3] == 'k'
                && beAt(blob, 44) == blob.length - 48) {
            start = 48;                              // FxCk program file
        }
        if (start < blob.length && (blob[start] == '{' || blob[start] == '[')) {
            return "plugin-specific JSON";
        }
        int n = Math.min(blob.length - start, 64);
        boolean text = n >= 4;
        for (int k = 0; k < n; k++) {
            int c = blob[start + k] & 0xFF;
            if (c != 0 && !(c == 9 || (c >= 32 && c < 127))) {
                text = false;
                break;
            }
        }
        return text ? "plugin-specific text" : "plugin-specific binary";
    }

    private static int beAt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static final Pattern VERSION_ATTR = Pattern.compile("\\bversion=\"([^\"]*)\"");
    private static final Pattern MFR_ATTR = Pattern.compile("\\bmanufacturer=\"([^\"]*)\"");
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname=\"([^\"]*)\"");

    /**
     * The folder for a path that has no room for one (e.g. /media/&lt;drive&gt;/name.so,
     * left by an older run): the &lt;Manufacturer&gt; - VST - &lt;Name&gt; convention both
     * build.py and legacy_convert.py create. Returns null when the attributes are missing.
     */
    private static String pluginFolderName(String text) {
        Matcher pm = PLUGIN.matcher(text);
        if (!pm.find()) {
            return null;
        }
        String el = pm.group();
        Matcher mm = MFR_ATTR.matcher(el);
        Matcher nm = NAME_ATTR.matcher(el);
        if (!mm.find() || !nm.find() || mm.group(1).isEmpty() || nm.group(1).isEmpty()) {
            return null;
        }
        // same illegal-character strip the folder builders apply
        String mfr = mm.group(1).replaceAll("[\"#@,;:<>*^|?\\\\/]", "");
        String name = nm.group(1).replaceAll("[\"#@,;:<>*^|?\\\\/]", "");
        return (mfr + " - VST - " + name).trim();
    }

    /** fxVersion for a rebuilt FxBk header, recovered from the preset's own version string. */
    private static int fxVersionOf(String text) {
        Matcher pm = PLUGIN.matcher(text);
        if (!pm.find()) {
            return Codec.FX_VERSION;
        }
        Matcher vm = VERSION_ATTR.matcher(pm.group());
        return vm.find() ? Codec.fxVersionOf(vm.group(1)) : Codec.FX_VERSION;
    }

    /** Rewrite the <PLUGIN file> path for the target layout. */
    private static Retarget retarget(String text, Options o) {
        Matcher m = PLUGIN.matcher(text);
        if (!m.find()) {
            throw new ConversionException("no <PLUGIN> element");
        }
        String element = m.group();
        Matcher fm = FILE_ATTR.matcher(element);
        if (!fm.find()) {
            throw new ConversionException("<PLUGIN> has no file attribute");
        }
        String old = fm.group(1);
        boolean rooted = old.replace("\\", "/").startsWith("/");
        String[] pp = parsePluginPath(old);                       // {root, folder, so}
        String folder = pp[1];
        if (splitPath(old).size() == 3) {
            // no root and no room for parent/folder (a stale path like
            // /media/<drive>/name.so): rebuild the folder from the plugin's own attributes
            String built = pluginFolderName(text);
            if (built != null && !built.isEmpty()) {
                folder = built;
            }
        }
        String base = stripArch(pp[2]);
        final String newSo;
        if ("sdk".equals(o.target)) {
            newSo = o.archSuffix ? base + "-" + o.arch + ".so" : base + ".so";
        } else {
            newSo = base + ".so";
        }
        boolean vstSeg = "sdk".equals(o.target) && o.vstSubdir;

        final String neu;
        if (o.installDir != null && !o.installDir.isEmpty()) {
            // The install folder says where the plugin lives on the device, and it COMPOSES
            // with derivation instead of replacing it: whatever is missing (parent, plugin
            // folder) is filled in, and everything already present -- a VST2 Plugins level,
            // the plugin folder, an extra VST/ subfolder -- is kept exactly as typed.  So
            // "/media/<drive>", "/media/<drive>/VST2 Plugins" and the full plugin folder all
            // reach the same shape.
            boolean abs = o.installDir.replace("\\", "/").startsWith("/");
            String hay = o.installDir.replace("\\", "/");
            if (!hay.endsWith("/")) {
                hay += "/";
            }
            List<String> parts = splitPath(o.installDir);
            String parent = "sdk".equals(o.target) ? o.sdkParent : o.origParent;
            if (!hay.contains("/" + folder + "/")) {      // does not reach the plugin folder yet
                if (!parent.isEmpty() && !hay.contains("/" + parent + "/")) {
                    parts.add(parent);
                }
                parts.add(folder);
            }
            parts.add(newSo);
            neu = (abs ? "/" : "") + String.join("/", parts);
        } else {
            String parent = "sdk".equals(o.target) ? o.sdkParent : o.origParent;
            List<String> tail = new ArrayList<>();
            tail.add(parent);
            tail.add(folder);
            if (vstSeg) {
                tail.add("VST");
            }
            tail.add(newSo);
            String root = pp[0];
            String joined = (root.isEmpty() ? "" : root + "/") + String.join("/", tail);
            neu = (rooted ? "/" : "") + joined;
        }

        if (neu.equals(old)) {
            return new Retarget(text, old, neu, false);
        }
        // fm was matched against the element, so splice within the element, then put it back
        String newElement = element.substring(0, fm.start(1)) + neu + element.substring(fm.end(1));
        return new Retarget(text.substring(0, m.start()) + newElement + text.substring(m.end()),
                old, neu, true);
    }

    /** Returns {rootPathJoined, folder, soFile} of a <PLUGIN file> path. */
    private static String[] parsePluginPath(String path) {
        List<String> parts = splitPath(path);
        int last = parts.size() - 1;
        if (parts.size() < 2 || !parts.get(last).endsWith(".so")) {
            throw new ConversionException("not a .so path: '" + path + "'");
        }
        if (parts.size() >= 4 && parts.get(last - 1).equals("VST")) {
            // <root>/<parent>/<Folder>/VST/<so>
            return new String[]{String.join("/", parts.subList(0, parts.size() - 4)),
                    parts.get(parts.size() - 3), parts.get(last)};
        }
        if (parts.size() >= 3) {
            // <root>/<parent>/<Folder>/<so>
            return new String[]{String.join("/", parts.subList(0, parts.size() - 3)),
                    parts.get(parts.size() - 2), parts.get(last)};
        }
        throw new ConversionException("path too short to classify: '" + path + "'");
    }

    private static List<String> splitPath(String path) {
        List<String> out = new ArrayList<>();
        for (String p : path.replace("\\", "/").split("/")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    static String stripArch(String so) {
        for (String a : Options.ARCHES) {
            String suffix = "-" + a + ".so";
            if (so.endsWith(suffix)) {
                return so.substring(0, so.length() - suffix.length());
            }
        }
        return so.endsWith(".so") ? so.substring(0, so.length() - 3) : so;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** .xpl files or folders to walk, in a stable order. */
    public static List<Path> gather(List<String> paths) {
        List<Path> out = new ArrayList<>();
        for (String p : paths) {
            Path path = Paths.get(p);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".xpl"))
                            .sorted()
                            .forEach(out::add);
                } catch (IOException e) {
                    throw new ConversionException("cannot walk " + p + ": " + e.getMessage(), e);
                }
            } else if (Files.isRegularFile(path)) {
                out.add(path);
            } else {
                throw new ConversionException("not found: " + p);
            }
        }
        return out;
    }
}
