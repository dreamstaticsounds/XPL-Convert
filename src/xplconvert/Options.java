package xplconvert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything one conversion run needs. Shared by the GUI, the CLI and the engine. */
public final class Options {
    public static final String SDK_PARENT = "VST2 Plugins";
    public static final String ORIG_PARENT = "Synths";
    public static final String[] ARCHES = {"arm64", "arm32"};

    /** "sdk" (VST2 Plugins + MPCP) or "original" (Synths + classic text chunk). */
    public String target;
    public String arch = "arm64";
    /** false = plain <name>.so, the name legacy_convert output keeps; true = <name>-arm64.so
     *  the way build.py names them. */
    public boolean archSuffix = true;
    /** false = the .so sits at the plugin folder root (legacy packages), true = VST/ subfolder
     *  (the build.py layout). */
    public boolean vstSubdir = true;
    public String sdkParent = SDK_PARENT;
    public String origParent = ORIG_PARENT;
    /** The plugin folder ON THE DEVICE; when set it overrides path derivation. */
    public String installDir;
    /** Keys that belong in the SDK's extra state rather than its parameter list. */
    public Set<String> extraKeys = new LinkedHashSet<>();
    /** Output folder; null = write beside each source using the suffix. */
    public String out;
    public String suffix;
    public boolean dryRun;
    public List<String> paths = new ArrayList<>();
}
