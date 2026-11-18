package com.emotune.cloud;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Cifrado AES-256-GCM para guardar API keys en la base de datos. La clave maestra viene de EMOTUNE_SECRET_KEY. */
public class SecretBox {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(String passphrase) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar el cifrado.", e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar.", e);
        }
    }

    /** Devuelve null si el texto no se puede descifrar (clave maestra cambiada o dato corrupto). */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length < 13) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
