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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HashingTest {

	// from https://www.md5hashgenerator.com/
	private final String md5hash = "1a79a4d60de6718e8e5b326e338ae533";
	private final String sha1hash = "c3499c2729730a7f807efb8676a92dcb6f8a3f8f";
	private final String sha256hash = "3bb12eda3c298db5de25597f54d924f2e17e78a26ad8953ed8218ee682f0bbbe9021e2f3009d152c911bf1f25ec683a902714166767afbd8e5bd0fb0124ecb8a";

	@Test
	public void hash_WithException() {
		assertThrows(IllegalArgumentException.class, () -> Hashing.hash(null, null));
		assertThrows(IllegalArgumentException.class, () -> Hashing.hash("example", null));
		assertThrows(IllegalArgumentException.class, () -> Hashing.hash(null, HashType.MD5));
	}

	@Test
	public void hash() {
		assertEquals(md5hash, Hashing.hash("example", HashType.MD5));
		assertEquals(sha1hash, Hashing.hash("example", HashType.SHA1));
		assertEquals(sha256hash, Hashing.hash("example", HashType.SHA512));
	}
}
