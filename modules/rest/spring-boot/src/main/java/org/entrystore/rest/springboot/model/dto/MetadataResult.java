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

package org.entrystore.rest.springboot.model.dto;

import java.util.Date;
import java.util.Objects;

/**
 * Result of a metadata retrieval operation.
 *
 * @param serializedGraph the serialized metadata graph, never null
 * @param lastModified    the latest modification date from traversal,
 *                        or null if not available (caller should fall back
 *                        to entry-level modification dates)
 */
public record MetadataResult(String serializedGraph,
							 Date lastModified) {

	public MetadataResult {
		Objects.requireNonNull(serializedGraph, "serializedGraph must not be null");
	}
}
