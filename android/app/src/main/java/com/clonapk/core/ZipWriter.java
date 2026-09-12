package com.clonapk.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Penulis ZIP dengan urutan entri deterministik (urutan penulisan v1 signing penting). */
final class ZipWriter {

    private ZipWriter() {
    }

    static byte[] write(LinkedHashMap<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(System.currentTimeMillis());
                if (name.endsWith("/")) {
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(0);
                    ze.setCompressedSize(0);
                    ze.setCrc(0);
                    zos.putNextEntry(ze);
                    zos.closeEntry();
                    continue;
                }
                if (data == null) {
                    data = new byte[0];
                }
                zos.putNextEntry(ze);
                zos.write(data);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** CRC32 untuk kebutuhan STORED entry. */
    static long crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }
}
