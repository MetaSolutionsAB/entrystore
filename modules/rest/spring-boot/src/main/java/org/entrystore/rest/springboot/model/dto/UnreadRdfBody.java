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

import org.apache.commons.io.function.IOSupplier;

import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * An RDF request body that has not been opened yet, so that a service can check access before reading it. Opening
 * the stream makes Jetty answer {@code Expect: 100-continue}.
 *
 * @param contentLength the declared length, or -1 when unknown
 * @param mediaType     the validated RDF media type
 */
public record UnreadRdfBody(IOSupplier<InputStream> stream, long contentLength, String mediaType, Charset charset) {
}
