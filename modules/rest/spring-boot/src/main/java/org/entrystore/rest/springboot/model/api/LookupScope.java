/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.standalone.springboot.model.api;

import java.util.Arrays;

public enum LookupScope {
	ALL,
	LOCAL,
	EXTERNAL;

	public static LookupScope fromString(String input) {
		for (var value : values()) {
			if (value.name().equalsIgnoreCase(input)) {
				return value;
			}
		}
		throw new IllegalArgumentException(
				"Unknown lookup scope: '" + input + "'. Allowed values: " + Arrays.toString(values()));
	}
}
