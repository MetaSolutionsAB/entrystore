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

package org.entrystore.rest.springboot.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.impl.RepositoryProperties;

import java.net.URI;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EntryInfoUtil {

	private static final Set<IRI> ENTRY_PREDICATES = Set.of(RDF.TYPE, RepositoryProperties.resource,
			RepositoryProperties.metadata, RepositoryProperties.externalMetadata,
			RepositoryProperties.cachedExternalMetadata, RepositoryProperties.relation, RepositoryProperties.Created,
			RepositoryProperties.Modified);

	private static final Set<IRI> RESOURCE_PREDICATES = Set.of(RDF.TYPE, RepositoryProperties.homeContext);

	/**
	 * Returns the statements of an entry information graph that are shown to a caller who may read neither the
	 * entry's metadata nor its resource: what clients need to resolve, type and date the entry, and the home context
	 * of a user or group. This is an allow-list, so everything else stays hidden, such as the creator, contributors,
	 * ACL, file name, format and size, and any predicate added later.
	 *
	 * @param cachedExternalMetadataURI the subject of {@code es:cached}, or null for an entry without one
	 */
	public static Model reduceForNonReader(Model graph, URI entryURI, URI resourceURI, URI cachedExternalMetadataURI) {
		IRI entry = toIRI(entryURI);
		IRI resource = toIRI(resourceURI);
		IRI cachedExternalMetadata = cachedExternalMetadataURI != null ? toIRI(cachedExternalMetadataURI) : null;
		Model reduced = new LinkedHashModel(graph.getNamespaces());
		for (Statement statement : graph) {
			if (isKept(statement, entry, resource, cachedExternalMetadata)) {
				reduced.add(statement);
			}
		}
		return reduced;
	}

	private static boolean isKept(Statement statement, IRI entry, IRI resource, IRI cachedExternalMetadata) {
		Resource subject = statement.getSubject();
		IRI predicate = statement.getPredicate();
		return (subject.equals(entry) && ENTRY_PREDICATES.contains(predicate))
				|| (subject.equals(resource) && RESOURCE_PREDICATES.contains(predicate))
				|| (subject.equals(cachedExternalMetadata) && predicate.equals(RepositoryProperties.cached));
	}

	private static IRI toIRI(URI uri) {
		return SimpleValueFactory.getInstance().createIRI(uri.toString());
	}
}
