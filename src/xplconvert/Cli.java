package xplconvert;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command-line front end: same behaviour as the GUI, so conversions can be scripted. */
public final class Cli {
    private Cli() {
    }

    public static void usage(PrintStream out) {
        out.println("XPL Convert - convert MPC .xpl presets between the two install layouts");
        out.println();
        out.println("Usage:");
        out.println("  java -jar XPLConvert.jar --to <sdk|original> [options] <preset.xpl | folder> ...");
        out.println();
        out.println("Layouts:");
        out.println("  sdk       <root>/VST2 Plugins/<Folder>/VST/<name>-arm64.so   bare MPCP in <state64>");
        out.println("  original  <root>/Synths/<Folder>/<name>.so                  FxBk + text chunk in <state>");
        out.println();
        out.println("Options:");
        out.println("  --to sdk|original    target layout (required)");
        out.println("  --arch arm64|arm32   binary name for the SDK layout (default arm64); only the");
        out.println("                       arm64 build gets a suffix, arm32 stays <name>.so");
        out.println("  --no-arch-suffix     SDK build uses a plain <name>.so instead of <name>-arm64.so");
        out.println("                       (legacy_convert output keeps the package's original name)");
        out.println("  --no-vst-subdir      derived paths keep the .so at the plugin folder root instead of");
        out.println("                       VST/<name>.so (legacy_convert packages have no VST/ subfolder)");
        out.println("  --sdk-parent <name>  folder the SDK build installs under (default \""
                + Options.SDK_PARENT + "\")");
        out.println("  --orig-parent <name> folder the classic build installs under (default \""
                + Options.ORIG_PARENT + "\")");
        out.println("  --install-dir <path> where the plugin lives ON THE DEVICE (e.g. /media/<drive>):");
        out.println("                       parent and plugin folders are filled in when missing; a path");
        out.println("                       that already names the plugin folder is used as typed");
        out.println("  --out <dir>          output folder (default: beside each source, with a suffix)");
        out.println("  --suffix <text>      filename suffix when --out is not given (default -sdk / -orig)");
        out.println("  --dry-run            report only, write nothing");
        out.println("  -h, --help           show this help");
        out.println();
        out.println("Examples:");
        out.println("  java -jar XPLConvert.jar --to sdk --out out-sdk \"./presets\"");
        out.println("  java -jar XPLConvert.jar --to sdk --arch arm32 preset.xpl");
        out.println("  java -jar XPLConvert.jar --to sdk --no-arch-suffix --no-vst-subdir legacy.xpl");
        out.println("  java -jar XPLConvert.jar --to original --install-dir \"/media/1234/Synths/My Plugin\" old.xpl");
    }

    public static int run(String[] args) {
        Options o = new Options();
        boolean toSeen = false;
        final List<String> paths = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            try {
                switch (a) {
                    case "-h":
                    case "--help":
                        usage(System.out);
                        return 0;
                    case "--to":
                        o.target = value(args, ++i, a);
                        toSeen = true;
                        break;
                    case "--arch":
                        o.arch = value(args, ++i, a);
                        break;
                    case "--no-arch-suffix":
                        o.archSuffix = false;
                        break;
                    case "--no-vst-subdir":
                        o.vstSubdir = false;
                        break;
                    case "--sdk-parent":
                        o.sdkParent = value(args, ++i, a);
                        break;
                    case "--orig-parent":
                        o.origParent = value(args, ++i, a);
                        break;
                    case "--install-dir":
                        o.installDir = value(args, ++i, a);
                        break;
                    case "--out":
                        o.out = value(args, ++i, a);
                        break;
                    case "--suffix":
                        o.suffix = value(args, ++i, a);
                        break;
                    case "--dry-run":
                        o.dryRun = true;
                        break;
                    default:
                        if (a.startsWith("--") && a.contains("=")) {
                            // --flag=value form: expand in place and reprocess
                            List<String> expanded = new ArrayList<>(Arrays.asList(args));
                            expanded.remove(i);
                            expanded.addAll(i, Arrays.asList(a.split("=", 2)));
                            args = expanded.toArray(new String[0]);
                            continue;
                        }
                        if (a.startsWith("-") && !a.equals("-")) {
                            throw new ConversionException("unknown option: " + a);
                        }
                        paths.add(a);
                }
            } catch (ConversionException e) {
                System.err.println("error: " + e.getMessage());
                System.err.println("run with --help for usage");
                return 2;
            }
        }

        if (!toSeen || o.target == null) {
            System.err.println("error: --to sdk|original is required");
            System.err.println("run with --help for usage");
            return 2;
        }
        if (!"sdk".equals(o.target) && !"original".equals(o.target)) {
            System.err.println("error: invalid --to choice '" + o.target + "' (use sdk or original)");
            return 2;
        }
        boolean archOk = false;
        for (String arch : Options.ARCHES) {
            archOk |= arch.equals(o.arch);
        }
        if (!archOk) {
            System.err.println("error: invalid --arch '" + o.arch + "' (use arm64 or arm32)");
            return 2;
        }
        if (paths.isEmpty()) {
            System.err.println("error: no input given (a .xpl file or a folder)");
            System.err.println("run with --help for usage");
            return 2;
        }
        o.paths = paths;
        return Engine.run(o, System.out::println);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new ConversionException("missing value for " + flag);
        }
        return args[i];
    }
}
