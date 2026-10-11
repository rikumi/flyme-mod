package dev.rikumi.flymemod;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** CustomizeCenter 12.5.0 bf.i / gf.a icon-module decoder; never applies a theme. */
final class FlymeThemeArchive {
    static void extract(File theme, File output) throws Exception {
        try (ZipFile mtpk = new ZipFile(theme)) {
            var entry = mtpk.getEntry("icons");
            if (entry == null) throw new java.io.IOException("主题未包含图标库");
            try (BufferedInputStream source = new BufferedInputStream(mtpk.getInputStream(entry))) {
                source.mark(8);
                int a = source.read(), b = source.read();
                source.reset();
                if (a == 'P' && b == 'K') copy(source, output);
                else {
                    byte[] salt = new byte[8];
                    new java.io.DataInputStream(source).readFully(salt);
                    Cipher cipher = Cipher.getInstance("PBEWithMD5AndDES");
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance("PBEWithMD5AndDES")
                            .generateSecret(new PBEKeySpec(password())), new PBEParameterSpec(salt, 100));
                    try (CipherInputStream decoded = new CipherInputStream(source, cipher)) { copy(decoded, output); }
                }
            }
        }
        // Reject unsupported formats before publishing the cache file.
        try (ZipFile ignored = new ZipFile(output)) {}
    }

    private static char[] password() throws Exception {
        byte[] key = Arrays.copyOf("meizu".getBytes(StandardCharsets.UTF_8), 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        String encoded = "fe55728039bcd6d19010b498a98905cf";
        byte[] bytes = new byte[encoded.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(encoded.substring(i * 2, i * 2 + 2), 16);
        return new String(cipher.doFinal(bytes), StandardCharsets.UTF_8).toCharArray();
    }

    private static void copy(InputStream input, File target) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 64L * 1024 * 1024) throw new java.io.IOException("主题图标库超过 64MB");
                output.write(buffer, 0, count);
            }
        }
    }
}
