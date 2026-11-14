package xplconvert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The file-format layer: base64 flavours, the classic engine chunk, the SDK's MPCP chunk
 * and the FxBk wrapper the MPC writes.  Nothing here knows about any particular plugin.
 */
public final class Codec {
    private Codec() {
    }

    // ------------------------------------------------------------------ JUCE base64
    private static final String JUCE = ".ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final int[] JUCE_REV = new int[128];

    static {
        Arrays.fill(JUCE_REV, -1);
        for (int i = 0; i < JUCE.length(); i++) {
            JUCE_REV[JUCE.charAt(i)] = i;
        }
    }

    /** JUCE's variant: a decimal length, a ".", then 6-bit characters. */
    public static byte[] juceDecode(String s) {
        int dot = s.indexOf('.');
        if (dot < 0) {
            throw new ConversionException("bad JUCE base64: no length prefix");
        }
        final int n;
        try {
            n = Integer.parseInt(s.substring(0, dot));
        } catch (NumberFormatException e) {
            throw new ConversionException("bad JUCE base64 length prefix", e);
        }
        byte[] out = new byte[n];
        int acc = 0;
        int bits = 0;
        int bi = 0;
        for (int i = dot + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v = ch < 128 ? JUCE_REV[ch] : -1;
            if (v < 0) {
                throw new ConversionException("bad JUCE base64 character '" + ch + "'");
            }
            acc |= v << bits;
            bits += 6;
            while (bits >= 8) {
                if (bi < n) {
                    out[bi] = (byte) (acc & 0xFF);
                }
                acc >>>= 8;
                bits -= 8;
                bi++;
            }
        }
        return out;
    }

    public static String juceEncode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        int bits = 0;
        for (byte b : data) {
            acc |= (b & 0xFF) << bits;
            bits += 8;
            while (bits >= 6) {
                sb.append(JUCE.charAt(acc & 0x3F));
                acc >>>= 6;
                bits -= 6;
            }
        }
        if (bits > 0) {
            sb.append(JUCE.charAt(acc & 0x3F));
        }
        return data.length + "." + sb;
    }

    // ------------------------------------------------------------------ standard base64
    /** Standard base64, tolerating line breaks and stray characters like Python's b64decode. */
    public static byte[] stdDecode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=') {
                sb.append(c);
            }
        }
        String t = sb.toString();
        int rem = t.length() % 4;
        if (rem != 0) {
            t = t.substring(0, t.length() - rem) + "====".substring(0, 4 - rem);
        }
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            throw new ConversionException("bad base64 in state: " + e.getMessage(), e);
        }
    }

    public static String stdEncode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decode(String body, boolean juce) {
        return juce ? juceDecode(body) : stdDecode(body);
    }

    public static String encode(byte[] data, boolean juce) {
        return juce ? juceEncode(data) : stdEncode(data);
    }

    // ------------------------------------------------------------------ locating the chunk
    /** Where the engine chunk sits inside a decoded state blob. */
    public static final class ChunkLoc {
        public final int start;
        public final int length;
        public final int sizeAt;

        public ChunkLoc(int start, int length, int sizeAt) {
            this.start = start;
            this.length = length;
            this.sizeAt = sizeAt;
        }
    }

    /**
     * The chunk is either at byte 0 (bare) or inside an FxBk wrapper, where the MPC keeps a
     * big-endian length just before it -- for either chunk flavour (classic text or MPCP).
     * Returns null when the payload is in a format this tool does not know: that is not an
     * error, it is plugin-specific state (JSON, custom text, binary...) the caller passes
     * through untouched.
     */
    public static ChunkLoc findChunk(byte[] blob) {
        if (blob.length > 0 && isTextMagic(blob, 0) && (blob.length < 2 || blob[1] == '\t')) {
            int nul = indexOf(blob, 0, (byte) 0);
            if (nul > 0) {
                return new ChunkLoc(0, nul + 1, -1);
            }
        }
        for (int i = 0; i + 1 < blob.length; i++) {
            if (!isTextMagic(blob, i) || blob[i + 1] != '\t') {
                continue;
            }
            int nul = indexOf(blob, i, (byte) 0);
            if (nul < 0 || !printableTab(blob, i, nul)) {
                continue;
            }
            ChunkLoc r = wrapped(blob, i);
            if (r != null) {
                return r;
            }
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i + 4 <= blob.length; i++) {
            if (blob[i] == 'M' && blob[i + 1] == 'P' && blob[i + 2] == 'C' && blob[i + 3] == 'P') {
                hits.add(i);
            }
        }
        for (int at : hits) {
            ChunkLoc r = wrapped(blob, at);
            if (r != null) {
                return r;
            }
        }
        if (!hits.isEmpty()) {                       // plausible but no matching length field
            int at = hits.get(0);
            return new ChunkLoc(at, blob.length - at, at >= 4 ? at - 4 : -1);
        }
        return null;                                 // plugin-specific chunk: opaque to us
    }

    private static boolean isTextMagic(byte[] b, int i) {
        byte c = b[i];
        return c == '1' || c == '2' || c == '3';
    }

    private static ChunkLoc wrapped(byte[] blob, int at) {
        int sizeAt = at - 4;
        if (sizeAt >= 4) {
            int len = ((blob[sizeAt] & 0xFF) << 24) | ((blob[sizeAt + 1] & 0xFF) << 16)
                    | ((blob[sizeAt + 2] & 0xFF) << 8) | (blob[sizeAt + 3] & 0xFF);
            if (len == blob.length - at) {
                return new ChunkLoc(at, blob.length - at, sizeAt);
            }
        }
        return null;
    }

    private static int indexOf(byte[] b, int from, byte v) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == v) {
                return i;
            }
        }
        return -1;
    }

    private static boolean printableTab(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            int c = b[i] & 0xFF;
            if (!((c >= 32 && c < 127) || c == 9)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ classic text chunk
    public static final class ParsedText {
        public final String header;
        public final LinkedHashMap<String, String> kv;

        public ParsedText(String header, LinkedHashMap<String, String> kv) {
            this.header = header;
            this.kv = kv;
        }
    }

    public static ParsedText parseTextChunk(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\0') {
            end--;
        }
        text = text.substring(0, end);
        if (text.isEmpty() || "123".indexOf(text.charAt(0)) < 0
                || (text.length() > 1 && text.charAt(1) != '\t')) {
            throw new ConversionException("not a classic engine chunk (no 1/2/3<TAB> header)");
        }
        String[] toks = text.split("\t", -1);
        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        for (int i = 1; i < toks.length; i++) {
            int eq = toks[i].indexOf('=');
            if (eq >= 0) {
                kv.put(toks[i].substring(0, eq), toks[i].substring(eq + 1));
            }
        }
        return new ParsedText(toks[0], kv);
    }

    public static byte[] buildTextChunk(String header, Map<String, String> kv, List<String> order) {
        StringBuilder sb = new StringBuilder(header == null || header.isEmpty() ? "3" : header);
        Set<String> seen = new HashSet<>();
        for (String k : order) {
            if (kv.containsKey(k) && seen.add(k)) {
                sb.append('\t').append(k).append('=').append(kv.get(k));
            }
        }
        for (String k : kv.keySet()) {
            if (seen.add(k)) {
                sb.append('\t').append(k).append('=').append(kv.get(k));
            }
        }
        return (sb + "\0").getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ MPCP chunk (SDK)
    public static final class MpcpEntry {
        public final String id;
        public final double plain;

        public MpcpEntry(String id, double plain) {
            this.id = id;
            this.plain = plain;
        }
    }

    public static final class Mpcp {
        public final int version;
        public final List<MpcpEntry> entries;
        public final byte[] extra;

        public Mpcp(int version, List<MpcpEntry> entries, byte[] extra) {
            this.version = version;
            this.entries = entries;
            this.extra = extra;
        }
    }

    public static boolean isMpcp(byte[] b) {
        return b.length >= 4 && b[0] == 'M' && b[1] == 'P' && b[2] == 'C' && b[3] == 'P';
    }

    public static Mpcp parseMpcp(byte[] raw) {
        if (!isMpcp(raw)) {
            throw new ConversionException("not an MPCP chunk");
        }
        if (raw.length < 12) {
            throw new ConversionException("truncated MPCP header");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(4);
        int ver = bb.getInt();
        int n = bb.getInt();
        List<MpcpEntry> entries = new ArrayList<>(Math.max(n, 0));
        for (int i = 0; i < n; i++) {
            if (bb.position() >= raw.length) {
                throw new ConversionException("truncated MPCP entry");
            }
            int idlen = bb.get() & 0xFF;
            if (bb.position() + idlen + 8 > raw.length) {
                throw new ConversionException("truncated MPCP entry");
            }
            byte[] idb = new byte[idlen];
            bb.get(idb);
            String pid = new String(idb, StandardCharsets.UTF_8);
            float plain = bb.getFloat();
            bb.getFloat();                       // norm, unused
            entries.add(new MpcpEntry(pid, plain));
        }
        byte[] extra = new byte[0];
        if (bb.position() + 4 <= raw.length) {
            int elen = bb.getInt();
            if (elen > 0) {
                extra = new byte[Math.min(elen, raw.length - bb.position())];
                bb.get(extra);
            }
        }
        return new Mpcp(ver, entries, extra);
    }

    /**
     * norm is written as -1 on purpose: the SDK loader falls back to set(plain) whenever norm
     * is outside 0..1 (mpc_plugin.h, internalLoadState), so values land exactly without needing
     * the plugin's parameter table.
     */
    public static byte[] buildMpcp(List<MpcpEntry> entries, byte[] extra) {
        ByteBuffer bb = ByteBuffer.allocate(sizeOf(entries, extra)).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("MPCP".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(2);
        bb.putInt(entries.size());
        for (MpcpEntry e : entries) {
            byte[] id = e.id.getBytes(StandardCharsets.UTF_8);
            if (id.length > 255) {
                id = Arrays.copyOf(id, 255);
            }
            bb.put((byte) id.length);
            bb.put(id);
            bb.putFloat((float) e.plain);
            bb.putFloat(-1.0f);
        }
        bb.putInt(extra.length);
        bb.put(extra);
        return bb.array();
    }

    private static int sizeOf(List<MpcpEntry> entries, byte[] extra) {
        int size = 12 + 4 + extra.length;
        for (MpcpEntry e : entries) {
            int id = e.id.getBytes(StandardCharsets.UTF_8).length;
            size += 1 + Math.min(id, 255) + 8;
        }
        return size;
    }

    // ------------------------------------------------------------------ FxBk wrapper
    /** Fallback fxVersion when the preset carries no header and no parseable version. */
    public static final int FX_VERSION = 1000;

    /**
     * The 156-byte FxBk prefix the MPC writes (CcnK/FBCh + fxID + fxVersion), then the
     * big-endian chunk length.  Reused verbatim from a source preset when it has one;
     * otherwise the caller supplies the fxVersion recovered from the preset's version string.
     */
    public static byte[] fxbkHeader(String uidHex, int chunkLen, byte[] template, int fxVersion) {
        byte[] hdr = new byte[156];
        putAscii(hdr, 0, "CcnK");
        putAscii(hdr, 8, "FBCh");
        putBeInt(hdr, 12, 1);
        if (template != null && template.length >= 24) {
            System.arraycopy(template, 4, hdr, 4, 4);
            System.arraycopy(template, 16, hdr, 16, 8);       // fxID + fxVersion as the MPC wrote them
        } else {
            try {
                if (uidHex != null && uidHex.length() == 8) {
                    for (int i = 0; i < 4; i++) {
                        hdr[16 + i] = (byte) Integer.parseInt(uidHex.substring(i * 2, i * 2 + 2), 16);
                    }
                } else if (uidHex != null && !uidHex.isEmpty()) {
                    putBeInt(hdr, 16, (int) Long.parseLong(uidHex));
                } else {
                    putBeInt(hdr, 16, 0);
                }
            } catch (NumberFormatException e) {
                hdr[16] = hdr[17] = hdr[18] = hdr[19] = '?';
            }
            putBeInt(hdr, 20, fxVersion);
        }
        if (template != null && template.length >= 156) {
            System.arraycopy(template, 24, hdr, 24, 132);
        }
        byte[] out = Arrays.copyOf(hdr, 160);
        putBeInt(out, 156, chunkLen);
        return out;
    }

    /**
     * The MPC derives the dotted PLUGIN version from the plugin's integer version digit for
     * digit (1010 <-> "1.0.1.0", 1003 <-> "1.0.0.3"), so stripping the dots inverts it.
     */
    public static int fxVersionOf(String dotted) {
        String digits = dotted == null ? "" : dotted.replace(".", "");
        if (digits.isEmpty() || !digits.matches("\\d{1,9}")) {
            return FX_VERSION;
        }
        return Integer.parseInt(digits);
    }

    private static void putAscii(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[at + i] = (byte) s.charAt(i);
        }
    }

    private static void putBeInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    /** The SDK's extra state is 'k=v<TAB>k=v...'. */
    public static LinkedHashMap<String, String> splitExtra(byte[] extra) {
        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        for (String tok : new String(extra, StandardCharsets.UTF_8).split("\t", -1)) {
            int eq = tok.indexOf('=');
            if (eq >= 0) {
                kv.put(tok.substring(0, eq), tok.substring(eq + 1));
            }
        }
        return kv;
    }

    // ------------------------------------------------------------------ number formatting
    /** Python's round(): halves go to the even neighbour. */
    public static double pyRound(double v) {
        if (Double.isNaN(v)) {
            return v;
        }
        double f = Math.floor(v);
        double diff = v - f;
        if (diff > 0.5) {
            return f + 1;
        }
        if (diff < 0.5) {
            return f;
        }
        return (f % 2 == 0) ? f : f + 1;
    }

    /** text value -> number for an MPCP entry: whole numbers are snapped exactly. */
    public static double num(String v) {
        final double f;
        try {
            f = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
        double r = pyRound(f);
        if (Math.abs(f - r) < 1e-6) {
            return (double) ((long) r);
        }
        return f;
    }

    /** MPCP plain value -> text value, Python's "%d" for whole numbers and "%g" otherwise. */
    public static String txt(double plain) {
        double r = pyRound(plain);
        if (Math.abs(plain - r) < 1e-6) {
            return Long.toString((long) r);
        }
        return pyG(plain);
    }

    /** Python's "%g": 6 significant digits, rounded half-even on the exact value. */
    public static String pyG(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        boolean neg = v < 0 || (v == 0.0 && 1.0 / v < 0);
        BigDecimal bd = new BigDecimal(v).abs();
        if (bd.signum() == 0) {
            return "0";
        }
        int e = bd.precision() - bd.scale() - 1;
        if (e < -4 || e >= 6) {                                  // scientific notation
            BigDecimal rounded = bd.setScale(5 - e, RoundingMode.HALF_EVEN);
            int e2 = rounded.precision() - rounded.scale() - 1;
            if (e2 != e) {
                rounded = rounded.setScale(5 - e2, RoundingMode.HALF_EVEN);
            }
            String mant = stripTrailingZeros(rounded.movePointLeft(e2).toPlainString());
            String sign = e2 < 0 ? "-" : "+";
            String exp = String.format(java.util.Locale.ROOT, "%02d", Math.abs(e2));
            return (neg ? "-" : "") + mant + "e" + sign + exp;
        }
        BigDecimal rounded = bd.setScale(5 - e, RoundingMode.HALF_EVEN);
        return (neg ? "-" : "") + stripTrailingZeros(rounded.toPlainString());
    }

    private static String stripTrailingZeros(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }
}
