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

package org.entrystore.impl;

import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup guard for the Solr schema. EntryStore writes dynamic fields that only a matching {@code schema.xml}
 * declares, and the schema has no catch-all field, so an outdated schema rejects nearly every document. Because the
 * first boot after an upgrade wipes and rebuilds the index, that outcome would be an empty index whose version
 * markers say it is current. The check therefore runs before the wipe and refuses to start when the schema
 * verifiably lacks a required field, and equally when Solr answers 401 or 403 (Solr is up, the check may just not
 * read the schema) or 404 (the configured core does not exist, so every search and index batch would fail). A Solr
 * that cannot be reached, or that answers with another error such as 503 while the core is still loading, is
 * reported and let through: an outage keeps the established startup behaviour instead of becoming a startup
 * failure. The caller learns
 * whether the schema was verified, so it can be stricter about the version markers when it was not.
 */
public final class SolrSchemaCheck {

	private static final Logger log = LoggerFactory.getLogger(SolrSchemaCheck.class);

	private SolrSchemaCheck() {
	}

	/**
	 * Names of the dynamic fields the core declares, read through the read-only Schema API. Public so the
	 * integration tests can run exactly this read against a real Solr, since the guard fails open when it breaks.
	 */
	public static Set<String> dynamicFieldNames(SolrClient client) throws SolrServerException, IOException {
		Set<String> names = new LinkedHashSet<>();
		for (Map<String, Object> field : new SchemaRequest.DynamicFields().process(client).getDynamicFields()) {
			Object name = field.get("name");
			if (name != null) {
				names.add(name.toString());
			}
		}
		return names;
	}

	/** HTTP statuses that mean Solr answered but refused to show the schema, rather than being unavailable. */
	private static final int HTTP_UNAUTHORIZED = 401;

	private static final int HTTP_FORBIDDEN = 403;

	private static final int HTTP_NOT_FOUND = 404;

	/**
	 * @return {@code true} when the schema was read and declares every field of {@code required}, {@code false} when
	 * it could not be read and startup continues unverified
	 * @throws IllegalStateException when the schema could be read and lacks one of {@code required}, or when Solr
	 * answered the schema request with 401, 403 or 404
	 */
	public static boolean requireDynamicFields(SolrClient client, String solrUrl, Collection<String> required) {
		Set<String> declared;
		try {
			declared = dynamicFieldNames(client);
		} catch (RemoteSolrException e) {
			if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_FORBIDDEN) {
				throw new IllegalStateException("The Solr schema at " + solrUrl + " could not be read: Solr answered HTTP "
						+ e.code() + ". Grant the EntryStore Solr user permission to read the schema (the read-only Schema"
						+ " API) and start again; starting without the check could rebuild an empty index.", e);
			}
			if (e.code() == HTTP_NOT_FOUND) {
				throw new IllegalStateException("Solr answered HTTP 404 for the schema at " + solrUrl + ": the core does"
						+ " not exist. Check entrystore.solr.url, which must name the core, e.g."
						+ " http://host:8983/solr/<core>, and start again.", e);
			}
			log.error("Could not verify the Solr schema at {}: Solr answered HTTP {}. Continuing without the check",
					solrUrl, e.code(), e);
			return false;
		} catch (SolrServerException | IOException e) {
			log.warn("Could not verify the Solr schema at {}; continuing without the check", solrUrl, e);
			return false;
		}
		Set<String> missing = new TreeSet<>(required);
		missing.removeAll(declared);
		if (!missing.isEmpty()) {
			throw new IllegalStateException("The Solr schema at " + solrUrl + " lacks the dynamic field(s) " + missing
					+ " that this EntryStore version writes. Deploy the schema.xml shipped with this release, reload the"
					+ " core and start again; starting now would rebuild an empty index.");
		}
		log.info("Solr schema at {} declares the required dynamic fields {}", solrUrl, required);
		return true;
	}
}
