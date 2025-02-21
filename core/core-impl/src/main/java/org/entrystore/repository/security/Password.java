/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.codec.binary.Base64;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * Helper methods for handling hashed and salted passwords, using PBKDF2.
 * <p>
 * Inspired by Martin Konicek's code on StackOverflow.
 *
 * @author Hannes Ebner
 */
public class Password {

	private static final Logger log = LoggerFactory.getLogger(Password.class);

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

	@Getter
	@Setter
	private static Rules rules;

	@Getter
	private static final Rules defaultRules = new Rules(true, true, false, true, 10, null);

	@AllArgsConstructor
	@Getter
	@NoArgsConstructor
	public static class Rules {

		/**
		 * Upper case character required
		 */
		boolean uppercase;

		/**
		 * Lower case character required
		 */
		boolean lowercase;

		/**
		 * Symbol required
		 */
		boolean symbol;

		/**
		 * Number character required
		 */
		boolean number;

		/**
		 * Minimum password length
		 */
		int minLength;

		/**
		 * A set of regular expressions to match against
		 */
		Set<String> custom;

	}

	static {
		try {
			random = SecureRandom.getInstance("SHA1PRNG");
			secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

			long before = new Date().getTime();
			random.setSeed(random.generateSeed(saltLen));
			log.info("Seeding of SecureRandom took {} ms", new Date().getTime() - before);
		} catch (NoSuchAlgorithmException e) {
			log.error(e.getMessage());
		}
	}

	/**
	 * Computes a salted PBKDF2. Empty passwords are not supported.
	 */
	public static String getSaltedHash(String password) {
		checkMinimumRequirements(password);

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
		checkMinimumRequirements(password);

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
		checkMinimumRequirements(password);

		try {
			long before = new Date().getTime();
			SecretKey key = secretKeyFactory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, desiredKeyLen));
			log.info("Password hashing took {} ms", new Date().getTime() - before);
			return Base64.encodeBase64String(key.getEncoded());
		} catch (GeneralSecurityException gse) {
			log.error(gse.getMessage());
		}
		return null;
	}

	public static String sha256(String s) {
		MessageDigest digester;
		try {
			digester = MessageDigest.getInstance("SHA-256");
			digester.update(s.getBytes(StandardCharsets.UTF_8));
			byte[] key = digester.digest();
			SecretKeySpec spec = new SecretKeySpec(key, "AES");
			return Base64.encodeBase64String(spec.getEncoded());
		} catch (NoSuchAlgorithmException nsae) {
			log.error(nsae.getMessage());
		}
		return null;
	}

	private static void checkMinimumRequirements(String password) {
		if (password == null || password.isEmpty()) {
			throw new IllegalArgumentException("Empty passwords are not supported");
		}
		if (password.length() > PASSWORD_MAX_LENGTH) {
			throw new IllegalArgumentException("The length of the password must not exceed " + PASSWORD_MAX_LENGTH + " characters");
		}
	}

	public static boolean conformsToRules(String password) {
		try {
			checkMinimumRequirements(password);
		} catch (IllegalArgumentException iae) {
			return false;
		}

		if (rules == null) {
			rules = defaultRules;
		}

		if (password.length() < rules.getMinLength()) {
			return false;
		}

		if (rules.isUppercase() && !containsUpperCase(password)) {
			return false;
		}

		if (rules.isLowercase() && !containsLowerCase(password)) {
			return false;
		}

		if (rules.isNumber() && !containsNumber(password)) {
			return false;
		}

		if (rules.isSymbol() && !containsSymbol(password)) {
			return false;
		}

		if (rules.getCustom() != null) {
			for (String expression : rules.getCustom()) {
				if (!expression.isEmpty() && !Pattern.compile(expression).matcher(password).find()) {
					return false;
				}
			}
		}

		return true;
	}

	private static boolean containsLowerCase(String value) {
		return contains(value, i -> Character.isLetter(i) && Character.isLowerCase(i));
	}

	private static boolean containsUpperCase(String value) {
		return contains(value, i -> Character.isLetter(i) && Character.isUpperCase(i));
	}

	private static boolean containsNumber(String value) {
		return contains(value, Character::isDigit);
	}

	private static boolean containsSymbol(String value) {
		return Pattern.compile("[^a-zA-Z\\d]").matcher(value).find();
	}

	private static boolean contains(String value, IntPredicate predicate) {
		return value.chars().anyMatch(predicate);
	}

	public static void loadRules(Config config) {
		Rules rules = new Rules();
		rules.lowercase = config.getBoolean(Settings.AUTH_PASSWORD_RULE_LOWERCASE, defaultRules.isLowercase());
		rules.uppercase = config.getBoolean(Settings.AUTH_PASSWORD_RULE_UPPERCASE, defaultRules.isUppercase());
		rules.number = config.getBoolean(Settings.AUTH_PASSWORD_RULE_NUMBER, defaultRules.isNumber());
		rules.symbol = config.getBoolean(Settings.AUTH_PASSWORD_RULE_SYMBOL, defaultRules.isSymbol());
		rules.minLength = config.getInt(Settings.AUTH_PASSWORD_RULE_MINLENGTH, defaultRules.getMinLength());
		rules.custom = Sets.newHashSet(config.getStringList(Settings.AUTH_PASSWORD_RULE_CUSTOM));
	}

}
