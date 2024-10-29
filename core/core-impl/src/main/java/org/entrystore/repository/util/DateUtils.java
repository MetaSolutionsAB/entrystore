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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class DateUtils {

	public static long daysBetween(Date d1, Date d2) {
		if (d1 == null || d2 == null) {
			throw new IllegalArgumentException("Date must not be null");
		}

		LocalDateTime ld1 = LocalDateTime.ofInstant(d1.toInstant(), ZoneId.systemDefault());
		LocalDateTime ld2 = LocalDateTime.ofInstant(d2.toInstant(), ZoneId.systemDefault());

		return ChronoUnit.DAYS.between(ld1, ld2);
	}
}
