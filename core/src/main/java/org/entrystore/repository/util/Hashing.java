/**
 * Copyright (c) 2007-2010
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hashing utilities.
 * 
 * @author Hannes Ebner
 */
public class Hashing {
	
	private static final Logger log = LoggerFactory.getLogger(Hashing.class);

	private static String byteArrayToHexString(byte in[]) {
		byte ch = 0x00;
		int i = 0;

		if ((in == null) || (in.length < 1)) {
			return null;
		}

		String pseudo[] = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f" };
		StringBuffer out = new StringBuffer(in.length * 2);

		while (i < in.length) {
			ch = (byte) (in[i] & 0xF0);
			ch = (byte) (ch >>> 4);
			ch = (byte) (ch & 0x0F);
			out.append(pseudo[(int) ch]);
			ch = (byte) (in[i] & 0x0F);
			out.append(pseudo[(int) ch]);
			i++;
		}

		String rslt = new String(out);

		return rslt;
	}

	public static String md5(String source) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] bytes = md.digest(source.getBytes());
			return byteArrayToHexString(bytes);
		} catch (NoSuchAlgorithmException nsae) {
			log.warn("MD5 not supported: " + nsae.getMessage());
			return null;
		}
	}

	public static String sha(String source) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA");
			byte[] bytes = md.digest(source.getBytes());
			return byteArrayToHexString(bytes);
		} catch (NoSuchAlgorithmException nsae) {
			log.warn("SHA not supported: " + nsae.getMessage());
			return null;
		}
	}

}