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

import java.util.Date;

public class DateUtils {

	public static long daysBetween(Date d1, Date d2) {
		if (d1 == null || d2 == null) {
			throw new IllegalArgumentException("Date must not be null");
		}

		long oneHour = 60 * 60 * 1000L;
		return ((d2.getTime() - d1.getTime() + oneHour) / (oneHour * 24));
	}
}
