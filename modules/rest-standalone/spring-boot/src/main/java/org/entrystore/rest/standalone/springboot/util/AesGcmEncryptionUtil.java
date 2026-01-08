package org.entrystore.rest.standalone.springboot.util;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class AesGcmEncryptionUtil {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final String ALGORITHM_ABB = "AES";
	private static final int TAG_LENGTH_BIT = 128;
	private static final int IV_LENGTH_BYTE = 12;
	private static final int TARGET_RAW_BYTES = 96;
	private static final int MAX_PAYLOAD_LENGTH = 68;

	private final Cipher cipher = Cipher.getInstance(ALGORITHM);

	private final byte[] key;
	private final byte[] iv;

	public AesGcmEncryptionUtil(byte[] key) throws NoSuchPaddingException, NoSuchAlgorithmException {
		this.key = key;
		this.iv = new byte[IV_LENGTH_BYTE];
	}

	public String encrypt(String payload) {
		try {

			byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
			if (payloadBytes.length > MAX_PAYLOAD_LENGTH) {
				throw new RuntimeException("Payload too long fo 128-char limit.");
			}
			byte[] paddedPayload = new byte[MAX_PAYLOAD_LENGTH];
			System.arraycopy(payloadBytes, 0, paddedPayload, 0, payloadBytes.length);
			new SecureRandom().nextBytes(iv);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM_ABB), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			byte[] encrypted = cipher.doFinal(paddedPayload);

			ByteBuffer byteBuffer = ByteBuffer.allocate(TARGET_RAW_BYTES);
			byteBuffer.put(iv);
			byteBuffer.put(encrypted);

			return Base64.getUrlEncoder().withoutPadding().encodeToString(byteBuffer.array());
		} catch (Exception e) {
			throw new RuntimeException("Token generation failed", e);
		}
	}

	public String decrypt(String token) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(token);
			if (decoded.length != TARGET_RAW_BYTES) {
				return null;
			}

			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM_ABB), new GCMParameterSpec(TAG_LENGTH_BIT, Arrays.copyOfRange(decoded, 0 , IV_LENGTH_BYTE)));
			byte[] decrypted = cipher.doFinal(decoded, IV_LENGTH_BYTE, decoded.length - IV_LENGTH_BYTE);
			return new String(decrypted, StandardCharsets.UTF_8).trim();
		} catch (Exception e) {
			return null; // Invalid token
		}
	}
}
