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

package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.util.RawValue;

/**
 * The {@code resource} member of the entry JSON. Which variant applies follows from the entry's graph type, never
 * from the content: the text of a String entry may itself look like JSON.
 */
public sealed interface EntryResourceJson {

	/** JSON already serialized for the entry's graph type, embedded as is. */
	record Json(String json) implements EntryResourceJson {

		@JsonValue
		public RawValue raw() {
			return new RawValue(json);
		}
	}

	/** The text of a String entry, written as a JSON string. */
	record Text(@JsonValue String text) implements EntryResourceJson {
	}
}
