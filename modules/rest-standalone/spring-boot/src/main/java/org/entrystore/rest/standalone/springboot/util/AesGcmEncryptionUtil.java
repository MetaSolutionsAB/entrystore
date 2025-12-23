package org.entrystore.rest.standalone.springboot.util;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmEncryptionUtil {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final String ALGORITHM_ABB = "AES";
	private static final int TAG_LENGTH_BIT = 128;
	private static final int IV_LENGTH_BYTE = 12;
	private final Cipher cipher = Cipher.getInstance(ALGORITHM);

	private final byte[] key;
	private final byte[] iv;

	public AesGcmEncryptionUtil(byte[] key) throws NoSuchPaddingException, NoSuchAlgorithmException {
		this.key = key;
		this.iv = new byte[IV_LENGTH_BYTE];
	}

	public String encrypt(String payload) {
		try {
			new SecureRandom().nextBytes(iv);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM_ABB), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
		} catch (Exception e) {
			throw new RuntimeException("Token generation failed", e);
		}
	}

	public String decrypt(String token) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(token);
			System.arraycopy(decoded, 0, iv, 0, iv.length);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM_ABB), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			byte[] decrypted = cipher.doFinal(decoded, IV_LENGTH_BYTE, decoded.length - IV_LENGTH_BYTE);
			return new String(decrypted, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null; // Invalid token
		}
	}
}
