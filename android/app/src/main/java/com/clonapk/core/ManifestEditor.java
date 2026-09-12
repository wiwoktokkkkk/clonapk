package com.clonapk.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor AndroidManifest.xml biner (format AXML).
 *
 * <p>Ini bagian paling krusial dari proses clone: package name di dalam APK disimpan
 * sebagai XML biner terkompresi, bukan teks. Mengganti nama package tanpa memahami
 * format ini menghasilkan APK yang gagal di-parse instalasi.
 *
 * <p>Strategi: parse penuh seluruh file -> ubah string yang perlu -> serialisasi ulang.
 * Hasilnya selalu terverifikasi ulang dengan mem-parse outputnya, jadi kalau ada yang
 * melenceng kita gagal keras di sini, bukan nanti di perangkat pengguna.
 */
public final class ManifestEditor {

    /** Tipe chunk AXML. */
    private static final int CHUNK_AXML_FILE = 0x00080003;
    private static final int CHUNK_STRING_POOL = 0x001C0001;
    private static final int CHUNK_RESOURCE_MAP = 0x00080180;
    private static final int CHUNK_XML_START_NAMESPACE = 0x00100100;
    private static final int CHUNK_XML_END_NAMESPACE = 0x00100101;
    private static final int CHUNK_XML_START_ELEMENT = 0x00100102;
    private static final int CHUNK_XML_END_ELEMENT = 0x00100103;
    private static final int CHUNK_XML_CDATA = 0x00100104;

    /** android:name = 0x01010003 */
    private static final int RES_NAME = 0x01010003;
    /** android:authorities = 0x01010018 */
    private static final int RES_AUTHORITIES = 0x01010018;
    /** android:targetPackage = 0x01010021 */
    private static final int RES_TARGET_PACKAGE = 0x01010021;
    /** android:value = 0x01010024 */
    private static final int RES_VALUE = 0x01010024;
    /** android:permission = 0x01010006 */
    private static final int RES_PERMISSION = 0x01010006;
    /** android:process = 0x01010011 */
    private static final int RES_PROCESS = 0x01010011;
    /** android:package = 0x0101021b */
    private static final int RES_PACKAGE = 0x0101021B;
    /** android:versionCode = 0x0101021c */
    private static final int RES_VERSION_CODE = 0x0101021C;
    /** android:versionName = 0x0101021d */
    private static final int RES_VERSION_NAME = 0x0101021D;
    /** android:minSdkVersion = 0x0101020c */
    private static final int RES_MIN_SDK_VERSION = 0x0101020C;
    /** Penanda atribut yang tidak punya entri di resource map. */
    private static final int NO_RES_ID = 0;

    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;

    private static final int FLAG_UTF8 = 1 << 8;

    /** Jejak debug: aktifkan lewat -Dclonapk.axml.trace=true untuk melihat tiap chunk. */
    private static final boolean DEBUG_TRACE =
            Boolean.getBoolean("clonapk.axml.trace");

    public static class ManifestException extends RuntimeException {
        public ManifestException(String msg) {
            super(msg);
        }

        public ManifestException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Satu entri string pool. UTF-8 AXML menyimpan panjang char dan panjang byte terpisah. */
    private static final class Str {
        final String value;
        final int charLen;

        Str(String value, int charLen) {
            this.value = value;
            this.charLen = charLen;
        }
    }

    /** Atribut XML biner. */
    private static final class Attr {
        int ns, name, rawValue, typedSize, res0, dataType, data;
    }

    /** Node generik AXML. */
    private static final class Node {
        final int type;
        int lineNumber, comment;
        int ns, name;
        int attrStart, attrSize, attrCount, idIdx, classIdx, styleIdx;
        final List<Attr> attrs = new ArrayList<>();
        byte[] rawTail;

        Node(int type) {
            this.type = type;
        }
    }

    private final List<Str> strings = new ArrayList<>();
    private final List<Integer> resourceIds = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();

    private ManifestEditor() {
    }

    /**
     * Ganti identitas package di dalam AndroidManifest.xml biner.
     *
     * @param manifest       isi AndroidManifest.xml dari APK sumber
     * @param oldPackage     nama package asli
     * @param newPackage     nama package baru
     * @param deepScanValues kalau true, nilai meta-data / string lain yang ber-prefix
     *                       package lama ikut diganti (default false supaya konfigurasi
     *                       aplikasi asli seperti API key tidak rusak)
     * @return byte AndroidManifest.xml baru
     */
    public static byte[] changePackage(
            byte[] manifest, String oldPackage, String newPackage, boolean deepScanValues) {
        ManifestEditor ed = new ManifestEditor();
        ed.parse(manifest);
        ed.rewrite(oldPackage, newPackage, deepScanValues);
        byte[] out = ed.serialize();
        ed.verify(out, newPackage);
        return out;
    }

    /** Ambil nilai atribut {@code package} dari manifest biner. */
    public static String readPackage(byte[] manifest) {
        ManifestEditor ed = new ManifestEditor();
        ed.parse(manifest);
        for (Node n : ed.nodes) {
            if (n.type != CHUNK_XML_START_ELEMENT) {
                continue;
            }
            if (!"manifest".equals(ed.str(n.name))) {
                continue;
            }
            for (Attr a : n.attrs) {
                if (a.dataType == TYPE_STRING
                        && (resIdOf(ed, a) == RES_PACKAGE || "package".equals(ed.str(a.name)))) {
                    String v = ed.str(a.data);
                    if (v != null && v.indexOf('.') > 0) {
                        return v;
                    }
                }
            }
        }
        throw new ManifestException("atribut package tidak ditemukan di AndroidManifest.xml");
    }

    // ------------------------------------------------------- pembacaan ekstra

    /**
     * Baca {@code android:versionName} dari elemen {@code <manifest>}.
     *
     * @return nama versi, atau {@code null} jika tidak dideklarasikan
     */
    public static String readVersionName(byte[] manifest) {
        ManifestEditor ed = parseOnly(manifest);
        for (Node n : ed.nodes) {
            if (n.type != CHUNK_XML_START_ELEMENT || !"manifest".equals(ed.str(n.name))) {
                continue;
            }
            for (Attr a : n.attrs) {
                if (resIdOf(ed, a) == RES_VERSION_NAME) {
                    return ed.typedValueAsString(a);
                }
            }
        }
        return null;
    }

    /**
     * Baca {@code android:minSdkVersion} dari elemen {@code <uses-sdk>}.
     *
     * @return nilai minSdk, atau {@code null} jika tidak dideklarasikan
     */
    public static Integer readMinSdk(byte[] manifest) {
        return parseOnly(manifest).findMinSdk();
    }

    /** Kumpulkan nilai {@code android:name} dari elemen {@code <uses-permission>}. */
    public static List<String> collectPermissions(byte[] manifest) {
        ManifestEditor ed = parseOnly(manifest);
        List<String> out = new ArrayList<>();
        for (Node n : ed.nodes) {
            String elem = ed.str(n.name);
            if (n.type != CHUNK_XML_START_ELEMENT
                    || (!"uses-permission".equals(elem)
                    && !"uses-permission-sdk-23".equals(elem))) {
                continue;
            }
            for (Attr a : n.attrs) {
                if (resIdOf(ed, a) == RES_NAME) {
                    String v = ed.typedValueAsString(a);
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Set {@code android:minSdkVersion} pada {@code <uses-sdk>}.
     *
     * <p>Diperlukan karena APK yang hanya ditandatangani skema v1 tidak bisa dipasang
     * pada perangkat yang menegakkan skema v2+ (minSdk &gt;= 24).
     *
     * @return manifest baru, atau input apa adanya jika elemen uses-sdk tidak ada
     */
    public static byte[] setMinSdk(byte[] manifest, int value) {
        ManifestEditor ed = parseOnly(manifest);
        boolean changed = false;
        for (Node n : ed.nodes) {
            if (n.type != CHUNK_XML_START_ELEMENT || !"uses-sdk".equals(ed.str(n.name))) {
                continue;
            }
            for (Attr a : n.attrs) {
                if (resIdOf(ed, a) != RES_MIN_SDK_VERSION) {
                    continue;
                }
                if (a.dataType == TYPE_INT_DEC || a.dataType == TYPE_INT_HEX) {
                    a.data = value;
                    a.dataType = TYPE_INT_DEC;
                    changed = true;
                } else if (a.dataType == TYPE_STRING) {
                    int idx = newPkgIndex(ed, String.valueOf(value));
                    a.data = idx;
                    a.rawValue = idx;
                    changed = true;
                }
            }
        }
        if (!changed) {
            return manifest;
        }
        byte[] out = ed.serialize();
        // verifikasi ulang struktur + pastikan nilainya benar-benar berubah
        Integer after = readMinSdk(out);
        if (after == null || after != value) {
            throw new ManifestException(
                    "gagal menetapkan minSdkVersion ke " + value + " (hasil=" + after + ")");
        }
        return out;
    }

    private Integer findMinSdk() {
        for (Node n : nodes) {
            if (n.type != CHUNK_XML_START_ELEMENT || !"uses-sdk".equals(str(n.name))) {
                continue;
            }
            for (Attr a : n.attrs) {
                if (resId(a) != RES_MIN_SDK_VERSION) {
                    continue;
                }
                if (a.dataType == TYPE_INT_DEC || a.dataType == TYPE_INT_HEX) {
                    return a.data;
                }
                String v = typedValueAsString(a);
                if (v != null) {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Ambil nilai atribut sebagai string, apa pun tipe datanya. */
    private String typedValueAsString(Attr a) {
        switch (a.dataType) {
            case TYPE_STRING:
                return str(a.data);
            case TYPE_INT_DEC:
            case TYPE_INT_HEX:
            case TYPE_INT_BOOLEAN:
                return String.valueOf(a.data);
            default:
                return null;
        }
    }

    private static int newPkgIndex(ManifestEditor ed, String s) {
        return ed.newPkgIndex(s);
    }

    private static int resIdOf(ManifestEditor ed, Attr a) {
        return ed.resId(a);
    }

    private static ManifestEditor parseOnly(byte[] manifest) {
        ManifestEditor ed = new ManifestEditor();
        ed.parse(manifest);
        return ed;
    }

    // ---------------------------------------------------------------- parsing

    private void parse(byte[] b) {
        LittleEndianReader r = new LittleEndianReader(b);
        int magic = r.u32();
        if (magic != CHUNK_AXML_FILE) {
            throw new ManifestException(
                    "bukan AXML (magic=0x" + Integer.toHexString(magic) + ")");
        }
        int fileSize = r.u32();
        if (fileSize > b.length) {
            throw new ManifestException("ukuran file AXML melebihi buffer");
        }
        int end = Math.min(fileSize, b.length);

        while (r.pos() + 8 <= end) {
            int chunkStart = r.pos();
            int type = r.u32();
            int size = r.u32();
            if (size < 8 || chunkStart + size > end) {
                throw new ManifestException(
                        "ukuran chunk tidak wajar: type=0x" + Integer.toHexString(type)
                                + " size=" + size);
            }
            if (DEBUG_TRACE) {
                System.out.printf("[trace] @%d type=0x%08x size=%d%n", chunkStart, type, size);
            }
            switch (type) {
                case CHUNK_STRING_POOL:
                    parseStringPool(r, chunkStart, size);
                    break;
                case CHUNK_RESOURCE_MAP:
                    parseResourceMap(r, chunkStart, size);
                    break;
                case CHUNK_XML_START_NAMESPACE:
                case CHUNK_XML_END_NAMESPACE:
                case CHUNK_XML_START_ELEMENT:
                case CHUNK_XML_END_ELEMENT:
                case CHUNK_XML_CDATA:
                    parseXmlNode(b, r, type, chunkStart, size);
                    break;
                default:
                    // chunk tak dikenal dilewati utuh agar struktur tetap aman
                    break;
            }
            r.seek(chunkStart + size);
        }
    }

    private void parseStringPool(LittleEndianReader r, int chunkStart, int size) {
        int stringCount = r.u32();
        int styleCount = r.u32();
        int flags = r.u32();
        int stringsStart = r.u32();
        int stylesStart = r.u32();
        boolean utf8 = (flags & FLAG_UTF8) != 0;

        if (stringCount < 0 || stringCount > 1_000_000) {
            throw new ManifestException("stringCount tidak wajar: " + stringCount);
        }

        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = r.u32();
        }

        int absStringsStart = chunkStart + stringsStart;
        for (int i = 0; i < stringCount; i++) {
            r.seek(absStringsStart + offsets[i]);
            if (utf8) {
                readUleb128(r); // charLen (UTF-16 code units)
                int byteLen = readUleb128(r);
                byte[] raw = r.bytes(byteLen);
                r.u8(); // null terminator
                strings.add(new Str(new String(raw, java.nio.charset.StandardCharsets.UTF_8), 0));
            } else {
                int charLen = readLen16(r);
                byte[] raw = r.bytes(charLen * 2);
                r.u16(); // null terminator
                strings.add(new Str(
                        new String(raw, java.nio.charset.StandardCharsets.UTF_16LE), charLen));
            }
        }
        // Style (span) sengaja tidak dipertahankan: AXML untuk manifest praktis tidak
        // memakainya, dan mencoba mempertahankannya berisiko merusak offset.
        if (styleCount != 0) {
            System.err.println("[clonapk] peringatan: string pool punya " + styleCount
                    + " style yang akan dijatuhkan saat penulisan ulang");
        }
    }

    private void parseResourceMap(LittleEndianReader r, int chunkStart, int size) {
        int count = (size - 8) / 4;
        for (int i = 0; i < count; i++) {
            resourceIds.add(r.u32());
        }
    }

    private void parseXmlNode(byte[] b, LittleEndianReader r, int type, int chunkStart, int size) {
        Node n = new Node(type);
        n.lineNumber = r.u32();
        n.comment = r.u32();
        n.ns = r.u32();
        n.name = r.u32();

        if (type == CHUNK_XML_START_ELEMENT) {
            n.attrStart = r.u16();
            n.attrSize = r.u16();
            n.attrCount = r.u16();
            n.idIdx = r.u16();
            n.classIdx = r.u16();
            n.styleIdx = r.u16();
            int attrBase = chunkStart + 16 + n.attrStart;
            if (n.attrSize == 0) {
                n.attrSize = 20;
            }
            for (int i = 0; i < n.attrCount; i++) {
                r.seek(attrBase + i * n.attrSize);
                Attr a = new Attr();
                a.ns = r.u32();
                a.name = r.u32();
                a.rawValue = r.u32();
                a.typedSize = r.u16();
                a.res0 = r.u8();
                a.dataType = r.u8();
                a.data = r.u32();
                n.attrs.add(a);
            }
        } else if (type == CHUNK_XML_CDATA) {
            n.attrStart = r.u16();
            n.attrSize = r.u16();
            n.attrCount = r.u16();
            r.u16();
            r.u16();
            r.u16();
        } else {
            // START/END_NAMESPACE dan END_ELEMENT: header 8 byte + info node 16 byte.
            // Kalau chunk lebih panjang dari itu, sisanya disimpan apa adanya supaya
            // tidak ada byte yang hilang tanpa disadari.
            int consumed = 24;
            if (size > consumed) {
                r.seek(chunkStart + consumed);
                n.rawTail = r.bytes(size - consumed);
            }
        }
        nodes.add(n);
    }

    // -------------------------------------------------------------- rewriting

    /** Rencana penggantian satu nilai atribut. */
    private static final class AttrPlan {
        final Attr attr;
        final String newValue;

        AttrPlan(Attr attr, String newValue) {
            this.attr = attr;
            this.newValue = newValue;
        }
    }

    /**
     * Ganti identitas package.
     *
     * <p>Dilakukan dalam SATU tahap atas snapshot nilai asli. Versi dua tahap (atribut lalu
     * string pool) punya cacat: kalau package baru berawalan package lama
     * (mis. {@code com.foo} -> {@code com.foo.dual}), hasil tahap pertama akan diproses
     * lagi oleh tahap kedua dan menghasilkan {@code com.foo.dual.dual}.
     */
    private void rewrite(String oldPkg, String newPkg, boolean deepScanValues) {
        if (oldPkg == null || oldPkg.isEmpty()) {
            throw new ManifestException("package lama kosong");
        }
        ApkUtil.validatePackageName(newPkg);
        if (oldPkg.equals(newPkg)) {
            throw new ManifestException("package baru sama dengan yang lama");
        }

        // Snapshot nilai asli supaya tidak ada nilai hasil olahan yang diolah ulang.
        String[] original = new String[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            original[i] = strings.get(i).value;
        }

        // 1) Kumpulkan rencana penggantian atribut (belum ditulis).
        List<AttrPlan> attrPlans = new ArrayList<>();
        for (Node n : nodes) {
            if (n.type != CHUNK_XML_START_ELEMENT) {
                continue;
            }
            String elem = str(n.name);
            for (Attr a : n.attrs) {
                int id = resId(a);
                boolean always = id == RES_PACKAGE
                        || id == RES_AUTHORITIES
                        || id == RES_TARGET_PACKAGE
                        || id == RES_PERMISSION
                        || id == RES_PROCESS;
                boolean nameLike = id == RES_NAME
                        || (id == NO_RES_ID && "manifest".equals(elem));
                boolean valueLike = deepScanValues
                        && (id == RES_VALUE || id == NO_RES_ID || id == RES_NAME);
                if (!(always || nameLike || valueLike)) {
                    continue;
                }
                if (a.dataType == TYPE_STRING) {
                    int idx = stringIndex(a.data);
                    if (idx >= 0) {
                        String nv = replacePkgPrefix(original[idx], oldPkg, newPkg);
                        if (nv != null) {
                            attrPlans.add(new AttrPlan(a, nv));
                        }
                    }
                } else if (id == RES_NAME
                        && (a.dataType == TYPE_INT_BOOLEAN
                        || a.dataType == TYPE_INT_DEC
                        || a.dataType == TYPE_INT_HEX)
                        && a.data >= 0 && a.data < original.length) {
                    // sebagian tool menulis android:name sebagai indeks string
                    // dengan tipe integer, bukan TYPE_STRING
                    String nv = replacePkgPrefix(original[a.data], oldPkg, newPkg);
                    if (nv != null) {
                        attrPlans.add(new AttrPlan(a, nv));
                    }
                }
            }
        }

        // 2) Rencanakan penggantian entri string pool.
        int[] poolTarget = new int[strings.size()];
        java.util.Arrays.fill(poolTarget, -1);
        int poolPlanned = 0;
        for (int i = 0; i < original.length; i++) {
            String nv = replacePkgPrefix(original[i], oldPkg, newPkg);
            if (nv != null) {
                poolTarget[i] = newPkgIndex(nv);
                poolPlanned++;
            }
        }

        if (attrPlans.isEmpty() && poolPlanned == 0) {
            throw new ManifestException(
                    "tidak ada referensi package '" + oldPkg + "' yang bisa diganti");
        }

        // 3) Terapkan semuanya sekaligus.
        for (AttrPlan p : attrPlans) {
            int idx = newPkgIndex(p.newValue);
            p.attr.data = idx;
            p.attr.rawValue = idx;
        }
        for (int i = 0; i < poolTarget.length; i++) {
            if (poolTarget[i] >= 0) {
                String nv = strings.get(poolTarget[i]).value;
                strings.set(i, new Str(nv, nv.length()));
            }
        }
    }

    private int newPkgIndex(String s) {
        for (int i = 0; i < strings.size(); i++) {
            if (strings.get(i).value.equals(s)) {
                return i;
            }
        }
        strings.add(new Str(s, s.length()));
        return strings.size() - 1;
    }

    /**
     * Ganti prefix package pada satu string.
     *
     * @return string baru, atau {@code null} jika string tidak perlu diubah.
     */
    private static String replacePkgPrefix(String v, String oldPkg, String newPkg) {
        if (v == null) {
            return null;
        }
        // Sudah berawalan package baru: biarkan. Tanpa ini, kasus package baru yang
        // merupakan turunan package lama akan menghasilkan sufiks ganda.
        if (v.startsWith(newPkg + ".") || v.equals(newPkg)) {
            return null;
        }
        if (v.equals(oldPkg)) {
            return newPkg;
        }
        if (v.startsWith(oldPkg + ".")) {
            return newPkg + v.substring(oldPkg.length());
        }
        return null;
    }

    /**
     * Ambil ID resource untuk sebuah atribut.
     *
     * <p>Penting: pada AXML, {@code Attr.name} adalah <em>indeks string pool</em>
     * (nama atribut sebagai teks), bukan ID resource. ID resource-nya berada di
     * resource map dengan indeks yang sama. Atribut tanpa namespace (mis. atribut
     * {@code package} pada elemen manifest) tidak punya entri resource map sehingga
     * hasilnya {@code NO_RES_ID}.
     */
    private int resId(Attr a) {
        return (a.name >= 0 && a.name < resourceIds.size()) ? resourceIds.get(a.name) : NO_RES_ID;
    }

    private String str(int idx) {
        if (idx < 0 || idx == 0xFFFFFFFF || idx >= strings.size()) {
            return null;
        }
        return strings.get(idx).value;
    }

    private int stringIndex(int data) {
        return (data >= 0 && data < strings.size()) ? data : -1;
    }

    // ----------------------------------------------------------- serializing

    private byte[] serialize() {
        // Bangun ulang string pool lebih dulu supaya offset atribut final.
        Map<String, Integer> dedup = new LinkedHashMap<>();
        List<String> ordered = new ArrayList<>();
        int mapSize = resourceIds.size();
        int[] remap = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            String value = strings.get(i).value;
            // Entri yang dipetakan resource map (nama atribut) TIDAK boleh didedup:
            // menggeser indeksnya akan membuat resource map menunjuk atribut yang salah.
            if (i < mapSize) {
                remap[i] = ordered.size();
                ordered.add(value);
                continue;
            }
            Integer exist = dedup.get(value);
            if (exist == null) {
                dedup.put(value, ordered.size());
                ordered.add(value);
            }
            remap[i] = dedup.get(value);
        }
        for (Node n : nodes) {
            for (Attr a : n.attrs) {
                if (a.dataType == TYPE_STRING && a.data >= 0 && a.data < remap.length) {
                    a.data = remap[a.data];
                }
                if (a.rawValue >= 0 && a.rawValue < remap.length) {
                    a.rawValue = remap[a.rawValue];
                }
                if (resId(a) == RES_NAME
                        && (a.dataType == TYPE_INT_BOOLEAN
                        || a.dataType == TYPE_INT_DEC
                        || a.dataType == TYPE_INT_HEX)
                        && a.data >= 0 && a.data < remap.length) {
                    a.data = remap[a.data];
                }
            }
        }

        byte[] pool = buildStringPool(ordered);
        byte[] resMap = buildResourceMap(ordered.size());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Node n : nodes) {
            writeNode(body, n);
        }

        int total = 8 + pool.length + resMap.length + body.size();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(CHUNK_AXML_FILE);
        out.putInt(total);
        out.put(pool);
        out.put(resMap);
        out.put(body.toByteArray());
        return out.array();
    }

    private byte[] buildStringPool(List<String> ordered) {
        boolean utf8 = true;
        List<byte[]> encoded = new ArrayList<>(ordered.size());
        for (String s : ordered) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] utf = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeUleb128(o, utf16Length(s));
            writeUleb128(o, utf.length);
            o.write(utf, 0, utf.length);
            o.write(0);
            encoded.add(o.toByteArray());
        }

        int headerSize = 28;
        int offsetTableSize = ordered.size() * 4;
        int dataStart = headerSize + offsetTableSize;

        int[] offsets = new int[ordered.size()];
        int cursor = 0;
        for (int i = 0; i < encoded.size(); i++) {
            offsets[i] = cursor;
            cursor += encoded.get(i).length;
        }
        int padded = align4(cursor);
        int total = dataStart + padded;

        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0001);
        b.putShort((short) headerSize);
        b.putInt(total);
        b.putInt(ordered.size());
        b.putInt(0); // styleCount
        b.putInt(utf8 ? FLAG_UTF8 : 0);
        b.putInt(dataStart);
        b.putInt(0); // stylesStart
        for (int off : offsets) {
            b.putInt(off);
        }
        for (byte[] e : encoded) {
            b.put(e);
        }
        while (b.position() < total) {
            b.put((byte) 0);
        }
        return b.array();
    }

    /**
     * Resource map memetakan indeks string pool -> ID atribut resource.
     * Panjangnya dipadankan dengan jumlah string final (di-pad 0) supaya parser
     * Android tidak melihat ketidakcocokan antara pool dan map.
     */
    private byte[] buildResourceMap(int stringCount) {
        int n = stringCount;
        int total = 8 + n * 4;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0180);
        b.putShort((short) 8);
        b.putInt(total);
        for (int i = 0; i < n; i++) {
            b.putInt(i < resourceIds.size() ? resourceIds.get(i) : 0);
        }
        return b.array();
    }

    private void writeNode(ByteArrayOutputStream o, Node n) {
        ByteBuffer b;
        switch (n.type) {
            case CHUNK_XML_START_ELEMENT: {
                int total = 36 + 20 * n.attrs.size();
                b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(n.type);
                b.putInt(total);
                b.putInt(n.lineNumber);
                b.putInt(n.comment);
                b.putInt(n.ns);
                b.putInt(n.name);
                b.putShort((short) 20);
                b.putShort((short) 20);
                b.putShort((short) n.attrs.size());
                b.putShort((short) n.idIdx);
                b.putShort((short) n.classIdx);
                b.putShort((short) n.styleIdx);
                for (Attr a : n.attrs) {
                    b.putInt(a.ns);
                    b.putInt(a.name);
                    b.putInt(a.rawValue);
                    b.putShort((short) (a.typedSize == 0 ? 8 : a.typedSize));
                    b.put((byte) a.res0);
                    b.put((byte) a.dataType);
                    b.putInt(a.data);
                }
                break;
            }
            case CHUNK_XML_START_NAMESPACE:
            case CHUNK_XML_END_NAMESPACE: {
                b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(n.type);
                b.putInt(24);
                b.putInt(n.lineNumber);
                b.putInt(n.comment);
                b.putInt(n.ns);
                b.putInt(n.name);
                break;
            }
            case CHUNK_XML_END_ELEMENT: {
                b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(n.type);
                b.putInt(24);
                b.putInt(n.lineNumber);
                b.putInt(n.comment);
                b.putInt(n.ns);
                b.putInt(n.name);
                break;
            }
            case CHUNK_XML_CDATA: {
                b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(n.type);
                b.putInt(28);
                b.putInt(n.lineNumber);
                b.putInt(n.comment);
                b.putInt(n.ns);
                b.putInt(n.name);
                b.putShort((short) 8);
                b.putShort((short) 0);
                b.putShort((short) 0);
                b.putShort((short) 0);
                b.putShort((short) 0);
                b.putShort((short) 0);
                break;
            }
            default:
                throw new ManifestException(
                        "tipe node tidak didukung saat menulis: 0x"
                                + Integer.toHexString(n.type));
        }
        o.write(b.array(), 0, b.array().length);
    }

    private void verify(byte[] out, String expectedPkg) {
        String got = readPackage(out);
        if (!expectedPkg.equals(got)) {
            throw new ManifestException(
                    "verifikasi gagal: package hasil='" + got + "' diharapkan='"
                            + expectedPkg + "'");
        }
        // parse ulang penuh untuk memastikan struktur chunk konsisten
        ManifestEditor check = new ManifestEditor();
        check.parse(out);
    }

    // ------------------------------------------------------------- utilities

    private static int utf16Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c >= 0xD800 && c <= 0xDBFF) ? 2 : 1;
        }
        return n;
    }

    private static int align4(int v) {
        return (v + 3) & ~3;
    }

    private static int readUleb128(LittleEndianReader r) {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = r.u8();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 28) {
                throw new ManifestException("ULEB128 terlalu panjang");
            }
        }
    }

    private static void writeUleb128(ByteArrayOutputStream o, int v) {
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            o.write(b);
            if (v == 0) {
                return;
            }
        }
    }

    private static int readLen16(LittleEndianReader r) {
        int len = r.u16();
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | r.u16();
        }
        return len;
    }

    /** Nama atribut resource yang dikenal, untuk keperluan debug/logging. */
    public static String debugAttrName(int resId) {
        switch (resId) {
            case RES_NAME: return "android:name";
            case RES_AUTHORITIES: return "android:authorities";
            case RES_PACKAGE: return "android:package";
            case RES_PERMISSION: return "android:permission";
            case RES_PROCESS: return "android:process";
            case RES_VALUE: return "android:value";
            case RES_TARGET_PACKAGE: return "android:targetPackage";
            default: return "res:0x" + Integer.toHexString(resId);
        }
    }
}
