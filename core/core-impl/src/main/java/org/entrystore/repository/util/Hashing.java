/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.repository.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashing utilities.
 *
 * @author Hannes Ebner
 */
public class Hashing {

	private static final Logger log = LoggerFactory.getLogger(Hashing.class);

	private static String byteArrayToHexString(byte[] in) {
		byte ch;
		int i = 0;

		String[] pseudo = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
		StringBuilder out = new StringBuilder(in.length * 2);

		while (i < in.length) {
			ch = (byte) (in[i] & 0xF0);
			ch = (byte) (ch >>> 4);
			ch = (byte) (ch & 0x0F);
			out.append(pseudo[ch]);
			ch = (byte) (in[i] & 0x0F);
			out.append(pseudo[ch]);
			i++;
		}

		return new String(out);
	}

	public static String hash(String source, String algorithm) {
		if (algorithm == null || algorithm.isEmpty()) {
			throw new IllegalArgumentException("Algorithm cannot be null or empty.");
		}

		if (source == null || source.isEmpty()) {
			throw new IllegalArgumentException("Source cannot be null or empty.");
		}

		try {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			byte[] bytes = md.digest(source.getBytes());

			if (bytes == null || bytes.length < 1) {
				throw new IllegalArgumentException("Digest created empty bytes array.");
			}

			return byteArrayToHexString(bytes);
		} catch (NoSuchAlgorithmException ex) {
			log.warn("{} not supported: {}. Returning non-hashed value.", algorithm, ex.getMessage());
			return source;
		}
	}

}
