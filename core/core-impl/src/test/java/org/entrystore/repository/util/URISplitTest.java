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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class URISplitTest {

	private static final URI anyURI = URI.create("https://slashdot.org/example");
	private static final String anyURIStringBase = "https://slashdot.org/";
	private static final String contextURIString = "https://slashdot.org/12/";
	private static final String entryURIString = "https://slashdot.org/12/entry/13";
	private static final String resourceURIString = "https://slashdot.org/12/resource/13";
	private static final String metadataURIString = "https://slashdot.org/12/metadata/13";
	private static final String unknownURIString = "https://example.org/12/metadata/13";
	private static final String encodedURIStringPart = "https://slashdot.org/12%2Fentry%2F13";
	private static final String encodedURIStringFull = "https%3A%2F%2Fslashdot.org%2F12%2Fentry%2F13";

	@Test
	public void constructor_badURL() {
		try {
			new URISplit(anyURI, URI.create("s://slashdot/").toURL());
		} catch (MalformedURLException e) {
			assertEquals(e.getMessage(), "unknown protocol: s");
		}
	}

	@Disabled("To be discussed")
	@Test
	public void constructor_null() {
		URISplit uriSplit = new URISplit(URI.create(""), null);
		assertNull(uriSplit.getBase());
		assertNull(uriSplit.getContextId());
		assertNull(uriSplit.getMetadataURI());
		assertNull(uriSplit.getMetaMetadataURI());
		assertNull(uriSplit.getUriType());
		assertNull(uriSplit.getPath());
		assertNull(uriSplit.getContextURI());
		assertNull(uriSplit.getContextMetaMetadataURI());
		assertNull(uriSplit.getResourceURI());
		assertNull(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), uriSplit.getPath(), uriSplit.getId()));
	}

	@Test
	public void constructor_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getBase(), anyURIStringBase);
		assertEquals(uriSplit.getContextId(), "_contexts");
		assertEquals(uriSplit.getMetadataURI(), URI.create("https://slashdot.org/_contexts/metadata/example"));
		assertEquals(uriSplit.getMetaMetadataURI(), URI.create("https://slashdot.org/_contexts/entry/example"));
		assertEquals(uriSplit.getUriType(), URIType.Resource);
		assertEquals(uriSplit.getPath(), "resource");
		assertEquals(uriSplit.getContextURI(), URI.create("https://slashdot.org/_contexts"));
		assertEquals(uriSplit.getContextMetaMetadataURI(), URI.create("https://slashdot.org/_contexts/entry/_contexts"));
		assertEquals(uriSplit.getResourceURI(), anyURI);
		assertEquals(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), uriSplit.getPath(), uriSplit.getId()), URI.create("https://slashdot.org/_contexts/resource/example"));
	}

	@Test
	public void constructor_resource() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(resourceURIString), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.Resource);
	}

	@Test
	public void constructor_metaMetadata() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(entryURIString), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.MetaMetadata);
	}

	@Test
	public void constructor_metadata() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(metadataURIString), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.Metadata);
	}

	@Test
	public void constructor_unknown() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(unknownURIString), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.Unknown);
	}

	@Test
	public void constructor_encodedPart() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(encodedURIStringPart), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.MetaMetadata);
	}

	@Test
	public void constructor_encodedFull() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(encodedURIStringFull), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getUriType(), URIType.MetaMetadata);
	}

	@Test
	public void getContextURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getContextURI(), URI.create("https://slashdot.org/_contexts"));
	}

	@Test
	public void getContextURI_fromURI() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getContextURI(), URI.create("https://slashdot.org/_contexts"));
	}

	@Test
	public void getContextMetaMetadataURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getContextMetaMetadataURI(), URI.create("https://slashdot.org/_contexts/entry/_contexts"));
	}

	@Test
	public void getMetaMetadataURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getMetaMetadataURI(), URI.create("https://slashdot.org/_contexts/entry/example"));
	}

	@Test
	public void getMetadataURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getMetadataURI(), URI.create("https://slashdot.org/_contexts/metadata/example"));
	}

	@Test
	public void getResourceURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getResourceURI(), URI.create("https://slashdot.org/example"));
	}

	@Test
	public void getResourceURI_existingContext() throws MalformedURLException {
		URISplit uriSplit = new URISplit(URI.create(contextURIString), URI.create(anyURIStringBase).toURL());
		assertEquals(uriSplit.getResourceURI(), URI.create("https://slashdot.org/12"));
	}

	@Test
	public void createURI_ok() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), uriSplit.getPath(), uriSplit.getId()), URI.create("https://slashdot.org/_contexts/resource/example"));
	}

	@Disabled("To be discussed")
	@Test
	public void createURI_noId() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), uriSplit.getPath(), null), URI.create("https://slashdot.org/_contexts/resource"));
	}

	@Disabled("To be discussed")
	@Test
	public void createURI_noPath() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), null, uriSplit.getId()), URI.create("https://slashdot.org/_contexts/example"));
	}

	@Disabled("To be discussed")
	@Test
	public void createURI_noPathNoId() throws MalformedURLException {
		URISplit uriSplit = new URISplit(anyURI, URI.create(anyURIStringBase).toURL());
		assertEquals(URISplit.createURI(uriSplit.getBase(), uriSplit.getContextId(), null, null), URI.create("https://slashdot.org/_contexts"));
	}
}
