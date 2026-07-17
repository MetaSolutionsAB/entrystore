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

package org.entrystore.repository.util;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1088 (A4): the ACL pre-filter string must keep every clause the application-level
 * check could grant through — public flag, principal match on the read-relevant ACL fields,
 * administered contexts, references (authorized against their target), and no-entry-ACL
 * fallthrough — with principal URIs escaped for the Solr query parser.
 */
public class SolrAclPreFilterTest {

	private static final URI USER = URI.create("http://localhost:8181/_principals/resource/user1");
	private static final URI GUEST = URI.create("http://localhost:8181/_principals/resource/_guest");
	private static final URI GROUP = URI.create("http://localhost:8181/_principals/resource/group1");

	@Test
	public void filterContainsAllGrantClauses() {
		Set<URI> principals = new LinkedHashSet<>(List.of(USER, GUEST, GROUP));

		String fq = SolrSearchIndex.buildAclPreFilterQuery(USER, principals, List.of("http://localhost:8181/ctx1"));

		assertTrue(fq.startsWith("public:true"), "guest-readable docs must always pass: " + fq);
		assertTrue(fq.contains("acl.metadata.r:("), "read grants must pass");
		assertTrue(fq.contains("acl.metadata.rw:("), "WriteMetadata implies ReadMetadata");
		assertTrue(fq.contains("acl.admin:("), "entry administrators must pass");
		assertTrue(fq.contains("context:("), "administered contexts bypass entry ACLs entirely");
		assertTrue(fq.contains(" OR resource:"),
				"the self-access grant (a user reads its own user entry regardless of entry ACLs) "
						+ "must be modelled, or the caller's own entry is under-included");
		assertFalse(fq.contains("entryType:"),
				"references need no special clause — the backstop requires ReadMetadata on the "
						+ "referring entry itself, which the principal clauses already model");
		assertTrue(fq.contains("(*:* -acl.admin:[* TO *] -acl.metadata.r:[* TO *] -acl.metadata.rw:[* TO *])"),
				"entries without read-relevant entry ACLs are decided by the app-level backstop");
		assertFalse(fq.contains("acl.resource.r"), "resource ACLs are irrelevant to ReadMetadata");
	}

	@Test
	public void principalUrisAreEscapedForTheSolrParser() {
		Set<URI> principals = new LinkedHashSet<>(List.of(USER));

		String fq = SolrSearchIndex.buildAclPreFilterQuery(USER, principals, List.of());

		// unescaped "http://..." would be parsed as a field prefix and a regex/comment sequence
		assertTrue(fq.contains("http\\://localhost\\:8181/_principals/resource/user1")
						|| fq.contains("http\\:\\/\\/localhost\\:8181"),
				"principal URIs must be escaped: " + fq);
		assertFalse(fq.contains("context:("), "no administered contexts, no context clause");
	}

	@Test
	public void oversizedClauseCountSkipsThePreFilterInsteadOfFailingTheSearch() {
		// 340 principals * 3 ACL fields alone exceeds Solr's default maxBooleanClauses (1024);
		// such callers must fall back to backstop-only filtering, not a failed search.
		Set<URI> principals = new LinkedHashSet<>();
		for (int i = 0; i < 340; i++) {
			principals.add(URI.create("http://localhost:8181/_principals/resource/group" + i));
		}

		String fq = SolrSearchIndex.buildAclPreFilterQuery(USER, principals, List.of());

		assertNull(fq, "clause counts above MAX_PREFILTER_CLAUSES must disable the pre-filter");
	}

	@Test
	public void manyAdministeredContextsAloneAlsoSkipThePreFilter() {
		Set<URI> principals = new LinkedHashSet<>(List.of(USER, GUEST));
		List<String> contexts = new ArrayList<>();
		for (int i = 0; i < 600; i++) {
			contexts.add("http://localhost:8181/ctx" + i);
		}

		String fq = SolrSearchIndex.buildAclPreFilterQuery(USER, principals, contexts);

		assertNull(fq, "hundreds of administered contexts would exceed request-line limits");
	}
}
