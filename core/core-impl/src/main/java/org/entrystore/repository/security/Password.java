/*
 * Copyright (c) 2007-2017 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.repository.security;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;

/**
 * Helper methods for handling hashed and salted passwords, using PBKDF2.
 * 
 * Inspired by Martin Konicek's code on StackOverflow.
 * 
 * @author Hannes Ebner
 */
public class Password {
	
	private static Logger log = LoggerFactory.getLogger(Password.class);

	// Higher number of iterations causes more work for both the attacker and
	// our system when checking passwords. Optimally this should be dynamically
	// chosen depending on how many iterations the local machine manages in a
	// specific amount of time (e.g. < 10 ms); the amount of iterations should
	// then be stored together with hash and password.
	private static final int iterations = 10 * 1024;

	/*
	 * SecureRandom, which is based internally on the 160-bit SHA-1 hash
	 * function, can only generate 2^160 possible sequences of any length. So
	 * there is no point in using more than 160 bits of salt (20 bytes) with
	 * this generator.
	 */
	// We use a salt of 16 byte length
	private static final int saltLen = 16;

	private static final int desiredKeyLen = 192;
	
	public static final int PASSWORD_MAX_LENGTH = 2048;

	private static SecureRandom random;
	
	private static SecretKeyFactory secretKeyFactory;
	
	static {
		try {
			random = SecureRandom.getInstance("SHA1PRNG");
			secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
			
			long before = new Date().getTime();
			random.setSeed(random.generateSeed(saltLen));
			log.info("Seeding of SecureRandom took " + (new Date().getTime()-before) + " ms");
		} catch (NoSuchAlgorithmException e) {
			log.error(e.getMessage());
		}
	}

	/**
	 * Computes a salted PBKDF2. Empty passwords are not supported.
	 */
	public static String getSaltedHash(String password) {
		checkAgainstRules(password);

		byte[] salt = new byte[saltLen];
		random.nextBytes(salt);
		
		// return the salt along with the salted and hashed password
		return Base64.encodeBase64String(salt) + "$" + hash(password, salt);
	}

	/**
	 * Checks whether given plaintext password corresponds to a stored salted
	 * hash of the password.
	 */
	public static boolean check(String password, String stored) {
		checkAgainstRules(password);

		if (stored == null) {
			throw new IllegalArgumentException("Stored password must not be null");
		}
		String[] saltAndPass = stored.split("\\$");
		if (saltAndPass.length != 2) {
			return false;
		}
		String hashOfInput = hash(password, Base64.decodeBase64(saltAndPass[0]));
		if (hashOfInput != null) {
			return hashOfInput.equals(saltAndPass[1]);
		}
		return false;
	}

	private static String hash(String password, byte[] salt) {
		checkAgainstRules(password);

		try {
			long before = new Date().getTime();
			SecretKey key = secretKeyFactory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, desiredKeyLen));
			log.info("Password hashing took " + (new Date().getTime()-before) + " ms");
			return Base64.encodeBase64String(key.getEncoded());
		} catch (GeneralSecurityException gse) {
			log.error(gse.getMessage());
		}
		return null;
	}
	
	public static String getRandomBase64(int length) {
		byte[] result = new byte[length];
		random.nextBytes(result);
		return Base64.encodeBase64String(result);
	}
	
	public static String sha256(String s) {
		MessageDigest digester;
		try {
			digester = MessageDigest.getInstance("SHA-256");
			digester.update(s.getBytes("UTF-8"));
			byte[] key = digester.digest();
			SecretKeySpec spec = new SecretKeySpec(key, "AES");
			return Base64.encodeBase64String(spec.getEncoded());
		} catch (NoSuchAlgorithmException nsae) {
			log.error(nsae.getMessage());
		} catch (UnsupportedEncodingException uee) {
			log.error(uee.getMessage());
		}
		return null;
	}

	private static void checkAgainstRules(String password) {
		if (password == null || password.length() == 0) {
			throw new IllegalArgumentException("Empty passwords are not supported");
		}
		if (password.length() > PASSWORD_MAX_LENGTH) {
			throw new IllegalArgumentException("The length of the password must not exceed " + PASSWORD_MAX_LENGTH + " characters");
		}
	}

}