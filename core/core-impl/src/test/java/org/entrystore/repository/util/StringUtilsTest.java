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

package org.entrystore.repository.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringUtilsTest {

	@Test
	public void convertUnitStringToByteSizeWithException() {
		try {
			StringUtils.convertUnitStringToByteSize(null);
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Parameter must not be null or empty");
		}
	}

	@Test
	public void convertUnitStringToByteSizeKilo() {
		String input = "1k";
		long result = StringUtils.convertUnitStringToByteSize(input);
		assertEquals(1024L, result);
	}

	@Test
	public void convertUnitStringToByteSizeMega() {
		String input = "1m";
		long result = StringUtils.convertUnitStringToByteSize(input);
		assertEquals(1048576L, result);
	}

	@Test
	public void convertUnitStringToByteSizeGiga() {
		String input = "1g";
		long result = StringUtils.convertUnitStringToByteSize(input);
		assertEquals(1073741824L, result);
	}

	@Test
	public void convertUnitStringToByteSizeTera() {
		String input = "1t";
		long result = StringUtils.convertUnitStringToByteSize(input);
		assertEquals(1099511627776L, result);
	}

	@Test
	public void convertUnitStringToByteSizeNoUnit() {
		String input = "1000";
		long result = StringUtils.convertUnitStringToByteSize(input);
		assertEquals(1000L, result);
	}
}
