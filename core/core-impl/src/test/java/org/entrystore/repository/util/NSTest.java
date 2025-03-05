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

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

public class NSTest {

	@Test
	public void expand_noColon() {
		String abbreviatedURI = "www.example.com";
		URI uri = NS.expand(abbreviatedURI);
		assertNotNull(uri);
		assertEquals(abbreviatedURI, uri.toString());
	}

	@Test
	public void expand_multipleColons() {
		String abbreviatedURI = "this:is:test";
		URI uri = NS.expand(abbreviatedURI);
		assertNotNull(uri);
		assertEquals(abbreviatedURI, uri.toString());
	}

	@Test
	public void expand_badNamespace() {
		String abbreviatedURI = ":example";
		URI uri = NS.expand(abbreviatedURI);
		assertNull(uri);
	}
}
