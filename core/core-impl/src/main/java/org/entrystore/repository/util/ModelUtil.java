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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;

import java.util.Map;

/**
 * Rewrites IRIs inside an RDF4J {@link Model}. Every method returns a new model. Statement contexts
 * are carried over unchanged; the callers here write the result into an explicit named graph, which
 * overrides them.
 */
public final class ModelUtil {

	private ModelUtil() {
	}

	/**
	 * Replaces {@code from} with {@code to} wherever it is the subject. Objects are left alone, so a
	 * statement that points at {@code from} keeps pointing at it.
	 */
	public static Model replaceSubject(Model graph, IRI from, IRI to) {
		Model result = new LinkedHashModel();
		for (Statement statement : graph) {
			Resource subject = from.equals(statement.getSubject()) ? to : statement.getSubject();
			result.add(subject, statement.getPredicate(), statement.getObject(), statement.getContext());
		}
		return result;
	}

	/** Replaces {@code from} with {@code to} as subject, predicate and object alike. */
	public static Model replaceIRI(Model graph, IRI from, IRI to) {
		return replaceIRIs(graph, Map.of(from, to));
	}

	/** Applies every replacement as subject, predicate and object alike. */
	public static Model replaceIRIs(Model graph, Map<IRI, IRI> replacements) {
		Model result = new LinkedHashModel();
		for (Statement statement : graph) {
			Resource subject = statement.getSubject();
			IRI predicate = replacements.getOrDefault(statement.getPredicate(), statement.getPredicate());
			Value object = statement.getObject();
			if (subject instanceof IRI iri) {
				subject = replacements.getOrDefault(iri, iri);
			}
			if (object instanceof IRI iri) {
				object = replacements.getOrDefault(iri, iri);
			}
			result.add(subject, predicate, object, statement.getContext());
		}
		return result;
	}
}
