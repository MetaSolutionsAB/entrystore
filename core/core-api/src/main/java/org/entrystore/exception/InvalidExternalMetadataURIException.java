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

package org.entrystore.exception;

/**
 * Thrown when an external metadata URI is not acceptable, e.g. because it is in the repository but does not denote
 * an entry, or because it is set for an entry that has no external metadata URI, such as a Local or Link entry. The
 * message only contains URIs that the client submitted or that belong to the entry, the entry type and fixed text,
 * and may be returned to the client.
 */
public class InvalidExternalMetadataURIException extends IllegalArgumentException {

	public InvalidExternalMetadataURIException(String message) {
		super(message);
	}

}
