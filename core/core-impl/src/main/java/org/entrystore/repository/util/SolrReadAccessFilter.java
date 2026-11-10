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

import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.params.FacetParams;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryManager;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Restricts a Solr search to the documents whose entry metadata the caller may read, so that rows, the hit count and
 * facets cover only entries the caller may read. The {@code related.*} fields of a readable entry's document are not
 * checked again: the indexer copies a related entry's metadata into them. It evaluates in Solr what
 * {@link PrincipalManager#checkAuthenticatedUserAuthorized} decides for {@code ReadMetadata}; a change to that check
 * or to the indexed fields this filter reads must be made in both places. With P the caller's
 * {@linkplain PrincipalManager#getAclPrincipalsOfAuthenticatedUser() ACL principals}, a document is readable if
 * <ol>
 *     <li>P names an owner ({@code acl.admin}) of its context entry,</li>
 *     <li>P is in its own {@code acl.admin}, {@code acl.metadata.r} or {@code acl.metadata.rw},</li>
 *     <li>it has no ACL of its own and P is in its context entry's {@code acl.resource.r} or {@code acl.resource.rw},
 *     or</li>
 *     <li>its resource URI is the caller's URI, as for the caller's own entry.</li>
 * </ol>
 * The document of a reference with a local target ({@value SolrSearchIndex#FIELD_REFERENCED_ENTRY}) holds the
 * target's metadata, so it passes only if both the target's document and the reference itself are readable, as
 * {@link org.entrystore.impl.ContextManagerImpl#checkEntryMetadataReadable} requires. A reference whose target has
 * no document is not admitted, although that check admits it.
 *
 * <p>A context entry's document has {@code context} {@code {base}/_contexts} and its context's URI as
 * {@code resource}, which every document of that context has as {@code context}, so rules 1 and 3 are joins. Only Local
 * entries of graph type Context or SystemContext count as context entries: their resource URI cannot be changed, while
 * another entry in {@code _contexts}, such as a Link, could name any context's URI as its resource. Each join
 * runs with {@code score=none}, its from-side bounded by the number of context entries or of local references. Joins
 * only see their own shard, so the core must not be split into shards. Only clauses that are reused within one
 * request or are the same for every caller are {@code filter} clauses, which Solr caches.
 *
 * <p>The principals and URIs are passed as request parameters that the filter dereferences, so they need no escaping
 * and the filter text stays constant. Clients cannot set request parameters, and client query syntax that
 * dereferences a parameter is refused before it reaches Solr.
 */
record SolrReadAccessFilter(URI user, List<String> principals, String contextsResource) {

	private static final String SELF = "{!term f=resource v=$esacl.user}";

	private static final String LOCAL_REFERENCE = SolrSearchIndex.FIELD_REFERENCED_ENTRY + ":*";

	private static final String FILTER = "{!bool should=$esacl.direct should=$esacl.viaReference}";

	private static final String SELF_ONLY = "{!bool must=$esacl.self must_not=$esacl.localReference}";

	private static final Map<String, String> PARAMS = params();

	/** Sorted, so that one caller always sends the same request and hits the same filterCache entry. */
	SolrReadAccessFilter {
		principals = principals.stream().sorted().toList();
	}

	/**
	 * The filter for the thread's authenticated user, a thread without one counting as the guest; empty when the
	 * search must not be restricted: authorization checks are off, or the user is the admin or an admin-group member.
	 */
	static Optional<SolrReadAccessFilter> forAuthenticatedUser(RepositoryManager rm) {
		if (!rm.isCheckForAuthorization()) {
			return Optional.empty();
		}
		PrincipalManager pm = rm.getPrincipalManager();
		URI user = pm.getAuthenticatedUserURI();
		if (user == null) {
			user = pm.getGuestUser().getURI();
		} else if (pm.isUserAdminOrAdminGroup(user)) {
			return Optional.empty();
		}
		List<String> principals = pm.getAclPrincipalsOfAuthenticatedUser().stream().map(URI::toString).toList();
		String contextsResource = rm.getContextManager().getEntry().getResourceURI().toString();
		return Optional.of(new SolrReadAccessFilter(user, principals, contextsResource));
	}

	/**
	 * Adds the filter to {@code query} as one more filter query, leaving the client's filter queries in place, and
	 * raises every facet minimum count below 1 to 1: with 0, Solr would list terms that only unreadable documents hold.
	 */
	void applyTo(SolrQuery query) {
		query.set("esacl.user", user.toString());
		if (principals.isEmpty()) {
			// an unresolvable user: no ACL grants it anything (as in PrincipalManagerImpl.hasAccess), and a local
			// reference's document holds its target's metadata, which the self rule does not cover
			query.addFilterQuery(SELF_ONLY);
			query.set("esacl.self", SELF);
			query.set("esacl.localReference", LOCAL_REFERENCE);
		} else {
			query.addFilterQuery(FILTER);
			PARAMS.forEach(query::set);
			query.set("esacl.principals", String.join(" ", principals));
			query.set("esacl.contexts", contextsResource);
		}
		if (query.getBool(FacetParams.FACET, false)) {
			raiseToOne(query, FacetParams.FACET_MINCOUNT);
			for (String name : List.copyOf(query.getParameterNames())) {
				if (name.startsWith("f.") && name.endsWith("." + FacetParams.FACET_MINCOUNT)) {
					raiseToOne(query, name);
				}
			}
		}
	}

	/** An absent {@code facet.mincount} is raised too, since Solr's default for field facets is 0. */
	private static void raiseToOne(SolrQuery query, String param) {
		if (query.getInt(param, 0) < 1) {
			query.set(param, 1);
		}
	}

	private static Map<String, String> params() {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("esacl.direct", "{!bool filter=$esacl.readable must_not=$esacl.localReference}");
		params.put("esacl.viaReference", "{!bool must=$esacl.targetReadable filter=$esacl.readable}");
		params.put("esacl.targetReadable", "{!join from=uri to=" + SolrSearchIndex.FIELD_REFERENCED_ENTRY
				+ " score=none v=$esacl.readableTargets}");
		params.put("esacl.readableTargets", "{!bool filter=$esacl.readable filter=$esacl.referenced}");
		// the documents some local reference points to: the same for every caller, so cached once per searcher
		params.put("esacl.referenced", "{!join from=" + SolrSearchIndex.FIELD_REFERENCED_ENTRY
				+ " to=uri score=none v=$esacl.localReference}");
		params.put("esacl.localReference", LOCAL_REFERENCE);
		params.put("esacl.readable",
				"{!bool should=$esacl.contextOwner should=$esacl.ownAcl should=$esacl.inherited should=$esacl.self}");
		params.put("esacl.contextOwner", "{!join from=resource to=context score=none v=$esacl.ownedContexts}");
		params.put("esacl.ownedContexts", "{!bool filter=$esacl.contextEntry must=$esacl.admin}");
		params.put("esacl.ownAcl",
				"{!bool should=$esacl.admin should=$esacl.metadataRead should=$esacl.metadataWrite}");
		params.put("esacl.inherited", "{!bool filter=$esacl.noOwnAcl must=$esacl.contextReader}");
		params.put("esacl.contextReader", "{!join from=resource to=context score=none v=$esacl.readableContexts}");
		params.put("esacl.readableContexts", "{!bool filter=$esacl.contextEntry must=$esacl.resourceAccess}");
		params.put("esacl.resourceAccess", "{!bool should=$esacl.resourceRead should=$esacl.resourceWrite}");
		params.put("esacl.noOwnAcl", "{!bool should=$esacl.markedWithoutAcl should=$esacl.unmarkedWithoutAcl}");
		params.put("esacl.markedWithoutAcl", SolrSearchIndex.FIELD_ACL_EXPLICIT + ":false");
		// documents indexed before the marker existed: no ACL field at all means no ACL of their own
		params.put("esacl.unmarkedWithoutAcl", "*:* -" + SolrSearchIndex.FIELD_ACL_EXPLICIT + ":* -acl.admin:*"
				+ " -acl.metadata.r:* -acl.metadata.rw:* -acl.resource.r:* -acl.resource.rw:*");
		params.put("esacl.contextEntry", "{!bool filter=$esacl.inContexts filter=$esacl.contextKind}");
		params.put("esacl.inContexts", "{!term f=context v=$esacl.contexts}");
		params.put("esacl.contextKind", "+entryType:Local +graphType:(Context SystemContext)");
		params.put("esacl.self", SELF);
		params.put("esacl.admin", principalsIn("acl.admin"));
		params.put("esacl.metadataRead", principalsIn("acl.metadata.r"));
		params.put("esacl.metadataWrite", principalsIn("acl.metadata.rw"));
		params.put("esacl.resourceRead", principalsIn("acl.resource.r"));
		params.put("esacl.resourceWrite", principalsIn("acl.resource.rw"));
		return Collections.unmodifiableMap(params);
	}

	/** URIs contain no whitespace but may contain commas, the default separator of the terms parser. */
	private static String principalsIn(String field) {
		return "{!terms f=" + field + " separator=' ' v=$esacl.principals}";
	}
}
