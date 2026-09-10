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
 * verifiably lacks a required field. A Solr that cannot be reached, or one without the Schema API, is reported and
 * let through: an outage keeps the established startup behaviour instead of becoming a startup failure.
 */
final class SolrSchemaCheck {

	private static final Logger log = LoggerFactory.getLogger(SolrSchemaCheck.class);

	private SolrSchemaCheck() {
	}

	/** Names of the dynamic fields the core declares, read through the read-only Schema API. */
	static Set<String> dynamicFieldNames(SolrClient client) throws SolrServerException, IOException {
		Set<String> names = new LinkedHashSet<>();
		for (Map<String, Object> field : new SchemaRequest.DynamicFields().process(client).getDynamicFields()) {
			Object name = field.get("name");
			if (name != null) {
				names.add(name.toString());
			}
		}
		return names;
	}

	/**
	 * @throws IllegalStateException when the schema could be read and lacks one of {@code required}
	 */
	static void requireDynamicFields(SolrClient client, String solrUrl, Collection<String> required) {
		Set<String> declared;
		try {
			declared = dynamicFieldNames(client);
		} catch (SolrServerException | IOException | RemoteSolrException e) {
			log.warn("Could not verify the Solr schema at {}; continuing without the check: {}", solrUrl, e.getMessage());
			return;
		}
		Set<String> missing = new TreeSet<>(required);
		missing.removeAll(declared);
		if (!missing.isEmpty()) {
			throw new IllegalStateException("The Solr schema at " + solrUrl + " lacks the dynamic field(s) " + missing
					+ " that this EntryStore version writes. Deploy the schema.xml shipped with this release, reload the"
					+ " core and start again; starting now would rebuild an empty index.");
		}
		log.info("Solr schema at {} declares the required dynamic fields {}", solrUrl, required);
	}
}
