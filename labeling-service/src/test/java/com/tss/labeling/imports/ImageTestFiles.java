package com.tss.labeling.imports;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Small binary fixtures without initializing the integration-test database. */
final class ImageTestFiles {
    static byte[] image(String format)throws Exception {
        var out=new ByteArrayOutputStream();
        if(!ImageIO.write(new BufferedImage(8,6,BufferedImage.TYPE_INT_RGB),format,out))throw new IllegalArgumentException(format);
        return out.toByteArray();
    }
    static byte[] zip(Map<String,byte[]> files)throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            for(var item:files.entrySet()) {zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}
        }
        return out.toByteArray();
    }
    static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
