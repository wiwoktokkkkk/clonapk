package com.clonapk.core;

/**
 * Pembaca byte little-endian dengan kursor. Dipakai untuk mem-parsing struktur
 * biner Android (AXML / string pool) yang semuanya little-endian.
 *
 * <p>Semua metode melempar {@link ManifestEditor.ManifestException} jika kursor
 * melewati akhir buffer, supaya file rusak terdeteksi dini alih-alih menghasilkan
 * APK cacat yang diam-diam tidak bisa diinstal.
 */
public final class LittleEndianReader {

    private final byte[] buf;
    private int pos;

    public LittleEndianReader(byte[] buf) {
        this(buf, 0);
    }

    public LittleEndianReader(byte[] buf, int pos) {
        this.buf = buf;
        this.pos = pos;
    }

    public int pos() {
        return pos;
    }

    public void seek(int p) {
        require(p >= 0 && p <= buf.length, "seek di luar buffer: " + p);
        pos = p;
    }

    public void skip(int n) {
        require(n >= 0, "skip negatif: " + n);
        seek(pos + n);
    }

    public int remaining() {
        return buf.length - pos;
    }

    public int u8() {
        require(pos + 1 <= buf.length, "EOF saat membaca u8");
        return buf[pos++] & 0xFF;
    }

    public int u16() {
        require(pos + 2 <= buf.length, "EOF saat membaca u16");
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public int u32() {
        require(pos + 4 <= buf.length, "EOF saat membaca u32");
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public byte[] bytes(int n) {
        require(n >= 0, "panjang negatif: " + n);
        require(pos + n <= buf.length, "EOF saat membaca " + n + " byte");
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new ManifestEditor.ManifestException(msg);
        }
    }
}
