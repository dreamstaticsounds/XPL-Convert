# XPL Convert

A cross-platform (Linux / macOS / Windows) converter for **MPC `.xpl` presets**, with a
Swing GUI and an equivalent command line. No dependencies beyond Java itself.

The MPC records where a plugin lived when a preset was saved — install path, binary name,
uid — and matches presets back to plugins by that description. A preset written against one
install layout is invisible to the other. Two things differ between the layouts:

| | ORIGINAL (classic layout, e.g. Open MPC) | SDK (Akai's MPC Plugin SDK) |
|---|---|---|
| binary | `<root>/Synths/<Folder>/<name>.so` | `<root>/VST2 Plugins/<Folder>/VST/<name>-arm64.so` |
| state | FxBk-wrapped `3\tkey=value` text chunk in `<state>` | bare `MPCP` chunk in `<state64>` |

This tool rewrites **both** — the `<PLUGIN file>` path and the state itself — in either
direction, for **any** plugin. Nothing about any particular plugin is hardcoded: folder and
`.so` names are read from each preset and rebuilt for the target.

## Requirements

Java 17 or newer (a JRE is enough to run; a JDK is only needed to build).
Any OpenJDK build works — [Temurin](https://adoptium.net) is a good default.

## Building

```sh
# Linux / macOS
./build.sh
```

```bat
:: Windows (needs javac on PATH)
build.bat
```

This produces `XPLConvert.jar`. The scripts look for Java in `JAVA_HOME`, `PATH`,
`~/opt/jdk-*`, `/usr/lib/jvm/*` and the macOS JDK location.

## Running the GUI

```sh
# Linux / macOS (builds the jar first if missing)
./run.sh
```

```bat
:: Windows
XPLConvert.bat
```

Or just double-click `XPLConvert.jar`, or:

```sh
java -jar XPLConvert.jar
```

In the window:

1. **Presets** — add `.xpl` files or folders (drag & drop works; folders are walked).
2. **Convert to** — *SDK layout* or *Classic layout*; the `arm64`/`arm32` picker names the
   binary for the SDK layout.
3. **Options** (all optional):
   - *Install folder on device* — where the plugin lives on the target device. It
     **composes** with the parent folder instead of replacing it: whatever is missing
     (parent, plugin folder) is filled in, and anything already present — a
     `VST2 Plugins` level, the plugin folder, an extra `VST/` subfolder — is kept exactly
     as typed. All of these produce the same result: `/media/<drive>`,
     `/media/<drive>/VST2 Plugins`, or the full plugin folder. Leave it blank to derive
     from the preset's own path.
   - *Parent folder* — one field that fills itself in per layout: `VST2 Plugins` when the
     SDK layout is selected, `Synths` for classic. It is used when deriving paths (i.e.
     when no install folder is given); edit it for non-standard installs.
   - *Extra-state keys* — comma-separated keys that belong in the SDK's **extra state**
     rather than its parameter list (plugins that keep loaded file names there need this,
     e.g. `wave,wave2,wave3` for a noisetable-using synth). Default: none.
   - *Arch suffix* — on by default: SDK binary names come out `<name>-arm64.so` the way
     `build.py` writes them. Uncheck for a plain `<name>.so`, the name `legacy_convert.py`
     output keeps (single-architecture packages). Derived SDK paths always include the
     `VST/` subfolder `build.py` uses; for a legacy plugin with the binary at the folder
     root, give its folder in *Install folder on device* (or use `--no-vst-subdir` on the
     command line).
   - *Output folder* — blank means each converted file is written beside its source with a
     `-sdk`/`-orig` suffix.
   - *Dry run* — report only, write nothing.
4. **Convert** — the report streams into the log at the bottom.

Nothing is overwritten unless you point the output at the input. If two input presets share
a file name and would land on the same output path in a flat output folder, the second one
is refused with a clear error instead of silently overwriting the first.

## Command line

```sh
java -jar XPLConvert.jar --to <sdk|original> [options] <preset.xpl | folder> ...
```

```
--to sdk|original    target layout (required)
--arch arm64|arm32   binary name for the SDK layout (default arm64)
--no-arch-suffix     SDK binary name stays <name>.so with no -arm64/-arm32: the original
                     name legacy_convert.py output keeps (single-architecture packages)
--no-vst-subdir      derived paths keep the SDK binary at the plugin folder root instead
                     of VST/<name>.so, matching legacy_convert.py output
--sdk-parent <name>  folder the SDK build installs under (default "VST2 Plugins")
--orig-parent <name> folder the classic build installs under (default "Synths")
--install-dir <path> where the plugin lives ON THE DEVICE (e.g. /media/<drive>): parent
                     and plugin folders are filled in when missing; a path that already
                     names the plugin folder is used as typed
--extra-keys <list>  comma-separated keys for the SDK's extra state (default: none)
--out <dir>          output folder (default: beside each source, with a suffix)
--suffix <text>      filename suffix when --out is not given (default -sdk / -orig)
--dry-run            report only, write nothing
-h, --help           show usage
```

Examples:

```sh
# classic -> SDK, into a folder
java -jar XPLConvert.jar --to sdk --out out-sdk "./presets"

# SDK -> classic
java -jar XPLConvert.jar --to original --out out-classic out-sdk

# 64-bit SDK -> 32-bit SDK (state untouched, only the binary name changes)
java -jar XPLConvert.jar --to sdk --arch arm32 preset.xpl

# a plugin installed by the SDK's legacy_convert.py: plain name at the folder root
java -jar XPLConvert.jar --to sdk --no-arch-suffix --no-vst-subdir --out out-legacy legacy.xpl

# point a preset at wherever the plugin lives on another device
java -jar XPLConvert.jar --to sdk \
    --install-dir "/media/123456/VST2 Plugins/Dream Static Sounds - VST - Noisestorm/VST" \
    --out out-live1 preset.xpl
```

Exit code 0 = every file converted (or already in the target layout), non-zero = some failed.

## What conversion does

- **Paths** — the `<PLUGIN file>` attribute is rebuilt for the target layout: parent
  folder (`Synths` ↔ `VST2 Plugins`), the `VST/` segment, and the binary name
  (`name.so` ↔ `name-arm64.so`). The two SDK details are switchable because the SDK's own
  tools differ: `build.py` writes `VST/<slug>-arm64.so`, while `legacy_convert.py` output
  keeps whatever name and location the package had — usually `<name>.so` at the folder
  root for a single-architecture package (`--no-arch-suffix` / `--no-vst-subdir`).
  `--install-dir` composes with derivation instead of replacing it: it supplies the root,
  missing parent/plugin folders are filled in, and levels already present (a plugin
  folder, a `VST/` subfolder) are kept as typed. A path with no folder level at all
  (e.g. `/media/<drive>/name.so`) gets its folder rebuilt from the plugin's own
  `<manufacturer> - VST - <name>` attributes.
- **State, text → MPCP** — the chunk's own keys are kept, values are written as plain
  floats with `norm = -1`. The SDK loader falls back to the plain value whenever `norm`
  is unusable, so values are exact without needing the plugin's parameter table. Extra
  keys (if named) move to the chunk's extra section.
- **State, MPCP → text** — plain values from the chunk are re-wrapped in the same FxBk
  header the MPC writes (`CcnK`/`FBCh` + uid + fxVersion). The uid and fxVersion are
  taken from the preset itself (`fxVersion` recovered from `<PLUGIN version>`, since
  `1.0.1.0` ↔ `1010`), so classic output is shaped exactly like its own presets.
- **Plugin-specific state is passed through** — many plugins store their own chunk format
  (JSON, custom text, binary) inside the same FxBk wrapper the MPC writes. The converter
  does not touch those bytes: it rewrites the `<PLUGIN file>` path and leaves the state
  exactly as it found it, reporting `state kept as-is (plugin-specific …)` in the log.
  Only the two formats it can actually convert — the classic `key=value` chunk and the
  SDK's `MPCP` chunk — are rewritten.
- **File shape is preserved** — the state element stays a direct child of `<pluginstate>`
  right after `</preset>`, exactly where every preset the MPC writes keeps it, and the
  preset's own CRLF/LF line endings are kept.

## Notes on compatibility

- **Why a preset may not appear on another device:** the MPC matches presets to plugins by
  the recorded description. If the drive id or install path differs (`/media/662522/...` vs
  `/media/778899/...`), the description doesn't match — for *every* plugin, not just yours.
  Fix: `--install-dir` with the drive as it exists on that device (the parent and plugin
  folder are filled in automatically).
- Round-tripping a classic preset through the SDK layout preserves every key and value, the
  path, the uid, the file structure and line endings. The only thing not representable in
  an `MPCP` chunk is the *relative order* of extra-state keys inside the classic text chunk;
  they are appended after the parameters instead. Order has no effect on loading, and the
  result is stable (converting again is a byte-for-byte no-op).
- Akai's position on the SDK and `.xpl` files: *"the SDK does not generate `.xpl` files.
  Prefer built-in programs."* This is an independent tool for moving existing presets
  between installs.

## Verified

Against a 37-preset library (both directions, arm64 + arm32):

- XML well-formed: **37/37**
- path round-trip: **37/37** (byte-identical)
- uid round-trip: **37/37**
- values round-trip: **37/37** (0 differ, 0 missing)
- classic → SDK → classic, second pass byte-identical to the first (stable)
- converted SDK presets load in the plugin (`effSetChunk → 1`, all parameters and extra
  state intact)

## Layout

```
XPL Convert/
├── XPLConvert.jar        the application (build.sh output)
├── build.sh / build.bat  build from src/
├── run.sh                run on Linux/macOS (builds if missing)
├── XPLConvert.bat        run on Windows
├── MANIFEST.MF           jar manifest (Main-Class)
└── src/xplconvert/
    ├── Main.java         entry point: no args = GUI, args = CLI
    ├── Gui.java          the Swing window
    ├── Cli.java          command line parsing
    ├── Engine.java       runs a conversion, reports line by line
    ├── Converter.java    path rewriting + state conversion, per preset
    ├── Codec.java        base64 flavours, text chunk, MPCP, FxBk header, number formats
    ├── Options.java      one conversion run's settings
    └── ConversionException.java
```
