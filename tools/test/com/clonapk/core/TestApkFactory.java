package com.clonapk.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pembuat APK uji untuk memverifikasi mesin clone tanpa perlu perangkat Android.
 *
 * <p>Menulis AndroidManifest.xml biner (AXML) yang strukturnya sama dengan keluaran aapt2:
 * string pool + resource map + chunk namespace + elemen bersarang dengan atribut bertipe.
 * Dua varian dibuat: string pool UTF-8 (umum di APK modern) dan UTF-16 (APK lama).
 */
public final class TestApkFactory {

    private TestApkFactory() {
    }

    // ---- ID resource android yang dipakai ----
    private static final int R_THEME = 0x01010000;
    private static final int R_NAME = 0x01010003;
    private static final int R_LABEL = 0x01010001;
    private static final int R_ICON = 0x01010002;
    private static final int R_AUTHORITIES = 0x01010018;
    private static final int R_MIN_SDK = 0x0101020C;
    private static final int R_TARGET_SDK = 0x01010270;
    private static final int R_PACKAGE = 0x0101021B;
    private static final int R_VERSION_CODE = 0x0101021C;
    private static final int R_VERSION_NAME = 0x0101021D;
    private static final int R_VALUE = 0x01010024;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_REFERENCE = 0x01;

    // ----------------------------------------------------------------- model

    static final class AttrSpec {
        final int nsIdx; // -1 = tanpa namespace
        final int nameResId;
        final String nameString;
        final int dataType;
        final String stringValue;
        final int intValue;

        AttrSpec(int nsIdx, int nameResId, int dataType, String stringValue, int intValue) {
            this.nsIdx = nsIdx;
            this.nameResId = nameResId;
            this.nameString = null;
            this.dataType = dataType;
            this.stringValue = stringValue;
            this.intValue = intValue;
        }
    }

    static final class ElementSpec {
        final String name;
        final List<AttrSpec> attrs = new ArrayList<>();
        final List<ElementSpec> children = new ArrayList<>();

        ElementSpec(String name) {
            this.name = name;
        }

        ElementSpec attr(int nsIdx, int nameResId, int dataType, String sv, int iv) {
            attrs.add(new AttrSpec(nsIdx, nameResId, dataType, sv, iv));
            return this;
        }

        ElementSpec child(ElementSpec e) {
            children.add(e);
            return this;
        }
    }

    /** String pool yang sedang dibangun. */
    private static final class Pool {
        final List<String> strings = new ArrayList<>();

        int add(String s) {
            for (int i = 0; i < strings.size(); i++) {
                if (strings.get(i).equals(s)) {
                    return i;
                }
            }
            strings.add(s);
            return strings.size() - 1;
        }
    }

    // ----------------------------------------------------------- pembuat AXML

    /**
     * Bangun AndroidManifest.xml biner.
     *
     * @param pkg        nama package
     * @param minSdk     nilai android:minSdkVersion
     * @param utf8       true untuk string pool UTF-8, false untuk UTF-16
     */
    public static byte[] buildManifest(String pkg, int minSdk, boolean utf8) {
        ElementSpec root = new ElementSpec("manifest")
                .attr(0, R_PACKAGE, TYPE_STRING, pkg, 0)
                .attr(0, R_VERSION_CODE, TYPE_INT_DEC, null, 42)
                .attr(0, R_VERSION_NAME, TYPE_STRING, "1.7.3", 0)
                .child(new ElementSpec("uses-sdk")
                        .attr(1, R_MIN_SDK, TYPE_INT_DEC, null, minSdk)
                        .attr(1, R_TARGET_SDK, TYPE_INT_DEC, null, 34))
                .child(new ElementSpec("uses-permission")
                        .attr(1, R_NAME, TYPE_STRING, "android.permission.INTERNET", 0))
                .child(new ElementSpec("uses-permission")
                        .attr(1, R_NAME, TYPE_STRING, pkg + ".permission.C2D_MESSAGE", 0))
                .child(new ElementSpec("application")
                        .attr(1, R_LABEL, TYPE_STRING, "Aplikasi Uji", 0)
                        .attr(1, R_ICON, TYPE_REFERENCE, null, 0x7f080001)
                        .attr(1, R_THEME, TYPE_REFERENCE, null, 0x7f090002)
                        .child(new ElementSpec("activity")
                                .attr(1, R_NAME, TYPE_STRING, pkg + ".MainActivity", 0)
                                .child(new ElementSpec("intent-filter")
                                        .child(new ElementSpec("action")
                                                .attr(1, R_NAME, TYPE_STRING,
                                                        "android.intent.action.MAIN", 0))
                                        .child(new ElementSpec("category")
                                                .attr(1, R_NAME, TYPE_STRING,
                                                        "android.intent.category.LAUNCHER", 0))))
                        .child(new ElementSpec("service")
                                .attr(1, R_NAME, TYPE_STRING,
                                        "com.example.lib.SyncService", 0))
                        .child(new ElementSpec("provider")
                                .attr(1, R_NAME, TYPE_STRING,
                                        pkg + ".provider.FileProvider", 0)
                                .attr(1, R_AUTHORITIES, TYPE_STRING,
                                        pkg + ".fileprovider", 0))
                        .child(new ElementSpec("meta-data")
                                .attr(1, R_NAME, TYPE_STRING, "com.google.app.id", 0)
                                .attr(1, R_VALUE, TYPE_STRING, "u-9182736455", 0)));

        Pool pool = new Pool();

        // Urutan string pool mengikuti keluaran aapt2: nama atribut lebih dulu
        // (bagian ini yang dipetakan resource map), lalu prefix namespace, URI,
        // nama elemen, dan terakhir nilai string.
        LinkedHashMap<String, Integer> attrNameIdx = new LinkedHashMap<>();
        attrNameIdx.put("theme", pool.add("theme"));
        attrNameIdx.put("label", pool.add("label"));
        attrNameIdx.put("icon", pool.add("icon"));
        attrNameIdx.put("name", pool.add("name"));
        attrNameIdx.put("authorities", pool.add("authorities"));
        attrNameIdx.put("minSdkVersion", pool.add("minSdkVersion"));
        attrNameIdx.put("targetSdkVersion", pool.add("targetSdkVersion"));
        attrNameIdx.put("package", pool.add("package"));
        attrNameIdx.put("versionCode", pool.add("versionCode"));
        attrNameIdx.put("versionName", pool.add("versionName"));
        attrNameIdx.put("value", pool.add("value"));
        int attrCount = attrNameIdx.size();

        int androidIdx = pool.add("android");
        int nsResIdx = pool.add("http://schemas.android.com/apk/res/android");
        collectStrings(pool, root);

        byte[] poolBytes = buildPool(pool, utf8);
        byte[] resMap = buildResMap(attrNameIdx, attrCount);
        byte[] body = buildBody(pool, attrNameIdx, root, nsResIdx, androidIdx);

        int total = 8 + poolBytes.length + resMap.length + body.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x00080003);
        out.putInt(total);
        out.put(poolBytes);
        out.put(resMap);
        out.put(body);
        return out.array();
    }

    private static void collectStrings(Pool pool, ElementSpec e) {
        pool.add(e.name);
        for (AttrSpec a : e.attrs) {
            if (a.nameString != null) {
                pool.add(a.nameString);
            }
            if (a.dataType == TYPE_STRING && a.stringValue != null) {
                pool.add(a.stringValue);
            }
        }
        for (ElementSpec c : e.children) {
            collectStrings(pool, c);
        }
    }

    private static byte[] buildPool(Pool pool, boolean utf8) {
        List<byte[]> enc = new ArrayList<>();
        for (String s : pool.strings) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (utf8) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                uleb(o, s.length());
                uleb(o, b.length);
                o.write(b, 0, b.length);
                o.write(0);
            } else {
                byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
                int chars = s.length();
                if (chars >= 0x8000) {
                    throw new IllegalStateException("string uji terlalu panjang");
                }
                o.write(chars & 0xFF);
                o.write((chars >> 8) & 0xFF);
                o.write(b, 0, b.length);
                o.write(0);
                o.write(0);
            }
            enc.add(o.toByteArray());
        }
        int headerSize = 28;
        int dataStart = headerSize + pool.strings.size() * 4;
        int[] offsets = new int[pool.strings.size()];
        int cur = 0;
        for (int i = 0; i < enc.size(); i++) {
            offsets[i] = cur;
            cur += enc.get(i).length;
        }
        int total = dataStart + ((cur + 3) & ~3);
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0001);
        b.putShort((short) headerSize);
        b.putInt(total);
        b.putInt(pool.strings.size());
        b.putInt(0);
        b.putInt(utf8 ? (1 << 8) : 0);
        b.putInt(dataStart);
        b.putInt(0);
        for (int off : offsets) {
            b.putInt(off);
        }
        for (byte[] e : enc) {
            b.put(e);
        }
        while (b.position() < total) {
            b.put((byte) 0);
        }
        return b.array();
    }

    /**
     * Resource map memetakan indeks string pool awal (khusus nama atribut) ke ID resource.
     * Jumlah entri HARUS sama dengan banyaknya nama atribut, bukan seluruh string pool.
     */
    private static byte[] buildResMap(LinkedHashMap<String, Integer> attrNameIdx, int attrCount) {
        int[] ids = new int[attrCount];
        for (java.util.Map.Entry<String, Integer> e : attrNameIdx.entrySet()) {
            ids[e.getValue()] = resIdFor(e.getKey());
        }
        int total = 8 + attrCount * 4;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0180);
        b.putShort((short) 8);
        b.putInt(total);
        for (int id : ids) {
            b.putInt(id);
        }
        return b.array();
    }

    /** Peta nama atribut -> ID resource android (satu sumber kebenaran untuk uji). */
    private static final LinkedHashMap<String, Integer> ATTR_NAMES = new LinkedHashMap<>();

    static {
        ATTR_NAMES.put("theme", R_THEME);
        ATTR_NAMES.put("label", R_LABEL);
        ATTR_NAMES.put("icon", R_ICON);
        ATTR_NAMES.put("name", R_NAME);
        ATTR_NAMES.put("authorities", R_AUTHORITIES);
        ATTR_NAMES.put("minSdkVersion", R_MIN_SDK);
        ATTR_NAMES.put("targetSdkVersion", R_TARGET_SDK);
        ATTR_NAMES.put("package", R_PACKAGE);
        ATTR_NAMES.put("versionCode", R_VERSION_CODE);
        ATTR_NAMES.put("versionName", R_VERSION_NAME);
        ATTR_NAMES.put("value", R_VALUE);
    }

    private static int resIdFor(String attrName) {
        Integer id = ATTR_NAMES.get(attrName);
        if (id == null) {
            throw new IllegalArgumentException("nama atribut tak dikenal: " + attrName);
        }
        return id;
    }

    private static byte[] buildBody(Pool pool, LinkedHashMap<String, Integer> attrNameIdx,
                                    ElementSpec root, int nsResIdx, int androidIdx) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeChunk(o, 0x00100100, 0, 0xFFFFFFFF, nsResIdx, androidIdx);
        writeElements(o, pool, attrNameIdx, root, nsResIdx);
        writeChunk(o, 0x00100101, 0, 0xFFFFFFFF, nsResIdx, androidIdx);
        return o.toByteArray();
    }

    private static void writeElements(ByteArrayOutputStream o, Pool pool,
                                      LinkedHashMap<String, Integer> attrNameIdx,
                                      ElementSpec e, int nsResIdx) {
        int nameIdx = indexOf(pool, e.name);
        int total = 36 + 20 * e.attrs.size();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x00100102);
        b.putInt(total);
        b.putInt(1); // lineNumber
        b.putInt(0xFFFFFFFF); // comment
        b.putInt(0xFFFFFFFF); // ns (elemen tanpa prefix)
        b.putInt(nameIdx);
        b.putShort((short) 20);
        b.putShort((short) 20);
        b.putShort((short) e.attrs.size());
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) 0);
        for (AttrSpec a : e.attrs) {
            b.putInt(a.nsIdx == 1 ? nsResIdx : 0xFFFFFFFF);
            b.putInt(attrNameIdx.get(attrName(a)));
            int dataIdx = 0xFFFFFFFF;
            int data = a.intValue;
            if (a.dataType == TYPE_STRING) {
                dataIdx = indexOf(pool, a.stringValue);
                data = dataIdx;
            }
            b.putInt(a.dataType == TYPE_STRING ? dataIdx : 0xFFFFFFFF);
            b.putShort((short) 8);
            b.put((byte) 0);
            b.put((byte) a.dataType);
            b.putInt(data);
        }
        o.write(b.array(), 0, b.array().length);

        for (ElementSpec c : e.children) {
            writeElements(o, pool, attrNameIdx, c, nsResIdx);
        }

        ByteBuffer end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x00100103);
        end.putInt(24);
        end.putInt(1);
        end.putInt(0xFFFFFFFF);
        end.putInt(0xFFFFFFFF);
        end.putInt(nameIdx);
        o.write(end.array(), 0, end.array().length);
    }

    /** Nama atribut untuk ID resource yang dipakai pada spesifikasi atribut. */
    private static String attrName(AttrSpec a) {
        for (java.util.Map.Entry<String, Integer> e : ATTR_NAMES.entrySet()) {
            if (e.getValue() == a.nameResId) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException(
                "resId tak dikenal: 0x" + Integer.toHexString(a.nameResId));
    }

    private static void writeChunk(ByteArrayOutputStream o, int type, int line, int comment,
                                   int ns, int name) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(type);
        b.putInt(24);
        b.putInt(line);
        b.putInt(comment);
        b.putInt(ns);
        b.putInt(name);
        o.write(b.array(), 0, b.array().length);
    }

    private static int indexOf(Pool pool, String s) {
        return pool.strings.indexOf(s);
    }

    private static void uleb(ByteArrayOutputStream o, int v) {
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

    // ------------------------------------------------------------- APK palsu

    /**
     * Tulis APK uji lengkap: manifest biner + classes.dex palsu + resources.arsc palsu.
     * Ditandatangani v1 dengan kunci sementara supaya kondisinya mirip APK produksi.
     */
    public static File buildTestApk(File dest, String pkg, int minSdk, boolean utf8)
            throws Exception {
        byte[] manifest = buildManifest(pkg, minSdk, utf8);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("AndroidManifest.xml", manifest);
        entries.put("classes.dex", fakeDex());
        entries.put("resources.arsc", "arsc-placeholder".getBytes(StandardCharsets.UTF_8));
        entries.put("res/layout/main.xml", "<layout/>".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/x", "svc".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/data.json", "{\"k\":1}".getBytes(StandardCharsets.UTF_8));

        ApkSigner.SigningKey key = ApkSigner.generateKey("Uji Asli");
        byte[] signed = ApkSigner.sign(entries, key);
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(signed);
        }
        return dest;
    }

    private static byte[] fakeDex() {
        ByteBuffer b = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        b.put("dex\n035\0".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0); // checksum, diisi di bawah
        b.putLong(0);
        b.putInt(112);
        return b.array();
    }

    /** ZIP kosong, untuk uji kasus tepi. */
    public static File buildEmptyZip(File dest) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dest))) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("bukan apk".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return dest;
    }
}
