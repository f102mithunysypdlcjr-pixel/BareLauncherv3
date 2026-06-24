package com.bare.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency-free QR Code generator.
 *
 * <p>Self-contained on purpose: BareLauncher ships zero third-party
 * dependencies, so rather than pull in a QR library (or call a network QR
 * service, which would break offline) the launcher renders QR codes on-device
 * with this single class. Scope is deliberately trimmed to exactly what the
 * About screen needs:
 *
 * <ul>
 *   <li>Byte mode only (URLs are ASCII — encoded as UTF-8).</li>
 *   <li>Error-correction level M (good resilience for a screen-scanned code).</li>
 *   <li>Versions 1–10 (up to 154 data bytes at level M — far more than the
 *       ~70-char URLs we encode), with automatic smallest-version selection.</li>
 *   <li>All eight data masks evaluated; the lowest-penalty one is chosen, per
 *       the QR spec.</li>
 * </ul>
 *
 * <p>The algorithm (Galois-field arithmetic, Reed–Solomon ECC, block
 * interleaving, function-pattern placement, masking and penalty scoring)
 * follows the ISO/IEC 18004 specification. The structure is adapted from
 * Project Nayuki's MIT-licensed "QR Code generator" reference implementation,
 * reduced to the byte-mode / level-M / version-1-10 subset above.
 *
 * <p>All methods are static and allocation happens only when a code is built
 * (a rare, user-initiated event from the About screen), so there is no steady-
 * state cost on the launcher's hot paths.
 */
final class QrCode {

    private QrCode() { /* no instances */ }

    // ── Public entry point ───────────────────────────────────────────────

    /**
     * Encode {@code text} and render it to a square {@link Bitmap}.
     *
     * @param text       the data to encode (UTF-8 byte mode).
     * @param targetPx   desired bitmap edge length in pixels (approximate —
     *                   the result is the largest whole-module multiple that
     *                   fits, including a 4-module quiet zone).
     * @param dark       ARGB colour for the dark modules (e.g. 0xFF000000).
     * @param light      ARGB colour for the light modules / quiet zone.
     * @return a square bitmap, or {@code null} if the text doesn't fit in
     *         versions 1–10 at level M.
     */
    static Bitmap render(String text, int targetPx, int dark, int light) {
        boolean[][] modules = encode(text);
        if (modules == null) return null;
        int size = modules.length;
        final int quiet = 4;                 // spec-mandated quiet zone
        int total = size + quiet * 2;
        int scale = Math.max(1, targetPx / total);
        int dim = total * scale;

        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        // Fill light first (covers the quiet zone too).
        int[] row = new int[dim];
        for (int x = 0; x < dim; x++) row[x] = light;
        for (int y = 0; y < dim; y++) bmp.setPixels(row, 0, dim, 0, y, dim, 1);

        for (int my = 0; my < size; my++) {
            for (int mx = 0; mx < size; mx++) {
                if (!modules[my][mx]) continue;
                int px = (mx + quiet) * scale;
                int py = (my + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        bmp.setPixel(px + dx, py + dy, dark);
                    }
                }
            }
        }
        return bmp;
    }

    /** Convenience: black-on-white render. */
    static Bitmap render(String text, int targetPx) {
        return render(text, targetPx, Color.BLACK, Color.WHITE);
    }

    // ── Error-correction tables (level M only, versions 1–10) ────────────
    // Index by version (1..10); index 0 is unused.
    private static final int[] ECC_CODEWORDS_PER_BLOCK_M =
            { -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
    private static final int[] NUM_ERROR_CORRECTION_BLOCKS_M =
            { -1,  1,  1,  1,  2,  2,  4,  4,  4,  5,  5 };

    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 10;
    // Level M format indicator bits.
    private static final int ECL_FORMAT_BITS_M = 0b00;

    // ── Core: text → boolean module matrix (true = dark) ─────────────────

    /** Returns the QR module matrix, or {@code null} if it doesn't fit. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        // Pick the smallest version (1..10) whose level-M data capacity holds
        // the byte-mode segment: 4 mode bits + char-count bits + 8*len.
        int version = -1;
        for (int v = MIN_VERSION; v <= MAX_VERSION; v++) {
            int capacityBits = getNumDataCodewords(v) * 8;
            int ccBits = (v <= 9) ? 8 : 16;
            int neededBits = 4 + ccBits + data.length * 8;
            if (neededBits <= capacityBits) { version = v; break; }
        }
        if (version < 0) return null;   // too large for versions 1–10

        // Build the bit stream: mode (byte = 0100), char count, data bytes.
        BitBuffer bb = new BitBuffer();
        bb.appendBits(0b0100, 4);
        bb.appendBits(data.length, (version <= 9) ? 8 : 16);
        for (byte b : data) bb.appendBits(b & 0xFF, 8);

        int dataCapacityBits = getNumDataCodewords(version) * 8;
        // Terminator: up to four 0 bits.
        bb.appendBits(0, Math.min(4, dataCapacityBits - bb.length()));
        // Pad to a byte boundary.
        bb.appendBits(0, (8 - bb.length() % 8) % 8);
        // Pad bytes: alternating 0xEC, 0x11.
        for (int pad = 0xEC; bb.length() < dataCapacityBits; pad ^= 0xEC ^ 0x11) {
            bb.appendBits(pad, 8);
        }

        byte[] dataCodewords = bb.toBytes();
        byte[] allCodewords = addEccAndInterleave(version, dataCodewords);
        return buildMatrix(version, allCodewords);
    }

    // ── Codeword math ────────────────────────────────────────────────────

    /** Number of data (non-ECC) codewords for the version at level M. */
    private static int getNumDataCodewords(int ver) {
        return getNumRawDataModules(ver) / 8
                - ECC_CODEWORDS_PER_BLOCK_M[ver] * NUM_ERROR_CORRECTION_BLOCKS_M[ver];
    }

    /** Number of data-bearing modules (excludes all function patterns). */
    private static int getNumRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    /** Split data into blocks, compute Reed–Solomon ECC, and interleave. */
    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS_M[ver];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK_M[ver];
        int rawCodewords = getNumRawDataModules(ver) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, dat.length);
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
            blocks[i] = block;
        }

        // Interleave: data codewords column-wise, then ECC codewords.
        byte[] result = new byte[rawCodewords];
        for (int i = 0, idx = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                // Skip the unused leading cell of short blocks' data region.
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[idx++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private static byte[] reedSolomonComputeDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;             // monomial x^0
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    /** Multiply two GF(2^8) field elements (primitive polynomial 0x11D). */
    private static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ── Matrix construction, masking, penalty ────────────────────────────

    private static boolean[][] buildMatrix(int ver, byte[] allCodewords) {
        int size = ver * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        drawFunctionPatterns(ver, modules, isFunction);
        drawCodewords(ver, allCodewords, modules, isFunction);

        // Choose the lowest-penalty mask (0..7).
        int bestMask = 0;
        int minPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(modules, isFunction, mask);
            drawFormatBits(ver, mask, modules, isFunction);
            int penalty = getPenaltyScore(modules);
            if (penalty < minPenalty) { minPenalty = penalty; bestMask = mask; }
            applyMask(modules, isFunction, mask);   // XOR again to undo
        }
        applyMask(modules, isFunction, bestMask);
        drawFormatBits(ver, bestMask, modules, isFunction);
        return modules;
    }

    private static void drawFunctionPatterns(int ver, boolean[][] m, boolean[][] f) {
        int size = m.length;
        // Timing patterns.
        for (int i = 0; i < size; i++) {
            setFunction(m, f, 6, i, i % 2 == 0);
            setFunction(m, f, i, 6, i % 2 == 0);
        }
        // Three finder patterns (with separators) at the corners.
        drawFinderPattern(m, f, 3, 3);
        drawFinderPattern(m, f, size - 4, 3);
        drawFinderPattern(m, f, 3, size - 4);

        // Alignment patterns.
        int[] alignPos = getAlignmentPatternPositions(ver);
        int n = alignPos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // Skip the three that collide with the finder patterns.
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                    drawAlignmentPattern(m, f, alignPos[i], alignPos[j]);
                }
            }
        }

        // Reserve format (always) and version (v>=7) areas, then draw version.
        drawFormatBits(ver, 0, m, f);   // dummy mask; reserves & marks function
        drawVersion(ver, m, f);
    }

    private static void drawFinderPattern(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < m.length && y >= 0 && y < m.length) {
                    setFunction(m, f, x, y, dist != 2 && dist != 4);
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(m, f, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private static void drawFormatBits(int ver, int mask, boolean[][] m, boolean[][] f) {
        int data = ECL_FORMAT_BITS_M << 3 | mask;   // 5 bits
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;     // 15 bits

        // First copy: around the top-left finder.
        for (int i = 0; i <= 5; i++) setFunction(m, f, 8, i, getBit(bits, i));
        setFunction(m, f, 8, 7, getBit(bits, 6));
        setFunction(m, f, 8, 8, getBit(bits, 7));
        setFunction(m, f, 7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(m, f, 14 - i, 8, getBit(bits, i));

        // Second copy: split across the other two finders.
        int size = m.length;
        for (int i = 0; i < 8; i++) setFunction(m, f, size - 1 - i, 8, getBit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(m, f, 8, size - 15 + i, getBit(bits, i));
        setFunction(m, f, 8, size - 8, true);   // always-dark module
    }

    private static void drawVersion(int ver, boolean[][] m, boolean[][] f) {
        if (ver < 7) return;
        int rem = ver;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = ver << 12 | rem;   // 18 bits, no XOR mask

        int size = m.length;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunction(m, f, a, b, bit);
            setFunction(m, f, b, a, bit);
        }
    }

    private static void drawCodewords(int ver, byte[] data, boolean[][] m, boolean[][] f) {
        int size = m.length;
        int i = 0;   // bit index into the data
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;   // skip the vertical timing column
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) {
                        m[y][x] = getBit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                    // Remaining modules (remainder bits) stay false (light).
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (f[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0:  invert = (x + y) % 2 == 0; break;
                    case 1:  invert = y % 2 == 0; break;
                    case 2:  invert = x % 3 == 0; break;
                    case 3:  invert = (x + y) % 3 == 0; break;
                    case 4:  invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5:  invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6:  invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    case 7:  invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = false; break;
                }
                m[y][x] ^= invert;
            }
        }
    }

    private static int getPenaltyScore(boolean[][] m) {
        int size = m.length;
        int result = 0;
        final int N1 = 3, N2 = 3, N3 = 40, N4 = 10;

        // Rule 1: runs of 5+ same-colour modules in rows and columns.
        for (int y = 0; y < size; y++) {
            boolean color = false; int runLen = 0;
            for (int x = 0; x < size; x++) {
                if (m[y][x] == color) { runLen++; if (runLen == 5) result += N1; else if (runLen > 5) result++; }
                else { color = m[y][x]; runLen = 1; }
            }
        }
        for (int x = 0; x < size; x++) {
            boolean color = false; int runLen = 0;
            for (int y = 0; y < size; y++) {
                if (m[y][x] == color) { runLen++; if (runLen == 5) result += N1; else if (runLen > 5) result++; }
                else { color = m[y][x]; runLen = 1; }
            }
        }

        // Rule 2: 2x2 blocks of the same colour.
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += N2;
            }
        }

        // Rule 3: finder-like 1:1:3:1:1 patterns in rows and columns.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (x <= size - 7 && hasFinderLike(m, x, y, true)) result += N3;
                if (y <= size - 7 && hasFinderLike(m, x, y, false)) result += N3;
            }
        }

        // Rule 4: proportion of dark modules.
        int dark = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (m[y][x]) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += k * N4;
        return result;
    }

    /** Detects the 1:1:3:1:1 dark/light run that looks like a finder, with a
     *  4-module light margin on at least one side (per the QR spec). */
    private static boolean hasFinderLike(boolean[][] m, int x, int y, boolean horizontal) {
        boolean[] p = new boolean[7];
        for (int i = 0; i < 7; i++) p[i] = horizontal ? m[y][x + i] : m[y + i][x];
        boolean core = p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6];
        if (!core) return false;
        // Require 4 light modules immediately before or after the pattern.
        boolean beforeLight = true, afterLight = true;
        for (int i = 1; i <= 4; i++) {
            int bx = horizontal ? x - i : x;
            int by = horizontal ? y : y - i;
            int ax = horizontal ? x + 6 + i : x;
            int ay = horizontal ? y : y + 6 + i;
            if (inB(m, bx, by) && m[by][bx]) beforeLight = false;
            if (inB(m, ax, ay) && m[ay][ax]) afterLight = false;
        }
        return beforeLight || afterLight;
    }

    private static boolean inB(boolean[][] m, int x, int y) {
        return x >= 0 && y >= 0 && x < m.length && y < m.length;
    }

    private static int[] getAlignmentPatternPositions(int ver) {
        if (ver == 1) return new int[0];
        int numAlign = ver / 7 + 2;
        int step = (ver * 4 + 4) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private static void setFunction(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) return;
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static boolean getBit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    // ── Tiny bit buffer ──────────────────────────────────────────────────
    private static final class BitBuffer {
        private byte[] data = new byte[64];
        private int bitLength = 0;

        int length() { return bitLength; }

        void appendBits(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                int byteIndex = bitLength >>> 3;
                ensureCapacity(byteIndex + 1);
                int bit = (value >>> i) & 1;
                data[byteIndex] |= bit << (7 - (bitLength & 7));
                bitLength++;
            }
        }

        byte[] toBytes() {
            byte[] out = new byte[(bitLength + 7) / 8];
            System.arraycopy(data, 0, out, 0, out.length);
            return out;
        }

        private void ensureCapacity(int bytes) {
            if (bytes <= data.length) return;
            int n = data.length;
            while (n < bytes) n <<= 1;
            byte[] grown = new byte[n];
            System.arraycopy(data, 0, grown, 0, data.length);
            data = grown;
        }
    }
}
