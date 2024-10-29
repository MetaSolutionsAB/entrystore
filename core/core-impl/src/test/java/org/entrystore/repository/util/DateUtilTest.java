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

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DateUtilTest {

	@Test
	public void daysBetween_WithException() {
		try {
			DateUtils.daysBetween(null, null);
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Date must not be null");
		}
	}

	@Test
	public void daysBetween_10days() {
		Date d1 = new Date();
		Date d2 = new Date();
		d2.setDate(d2.getDate() + 10);
		long result = DateUtils.daysBetween(d1, d2);
		assertEquals(10, result);
	}

	@Test
	public void daysBetween_50days() {
		Date d1 = new Date();
		Date d2 = new Date();
		d2.setDate(d2.getDate() + 50);
		long result = DateUtils.daysBetween(d1, d2);
		assertEquals(50, result);
	}
}
