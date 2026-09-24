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

import java.net.URI;

/**
 * Thrown when the external metadata URI of a Reference or LinkReference entry refers to the entry itself. The
 * message only contains the two URIs and may be returned to the client that submitted the external metadata URI.
 */
public class SelfReferencingExternalMetadataException extends InvalidExternalMetadataURIException {

	public SelfReferencingExternalMetadataException(URI externalMetadataURI, URI entryURI) {
		super("The external metadata URI " + externalMetadataURI + " must not refer to entry " + entryURI + " itself");
	}

}
