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

package org.entrystore.rest.springboot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.ValidatingValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
public class ValidatorService {

	private static final ValueFactory VF = new ValidatingValueFactory();

	private static final int MAX_IRI_LENGTH_IN_ERROR = 200;
	private static final int MAX_ERRORS_IN_RESPONSE = 50;

	public void validate(String rdfBody, String mediaType) {
		Model graph = GraphUtil.deserializeGraph(rdfBody, mediaType);
		assertAllIrisValid(graph);
		assertWritableToTripleStore(graph);
	}

	private static void assertAllIrisValid(Model graph) {
		Set<String> errors = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		for (Statement s : graph) {
			if (errors.size() >= MAX_ERRORS_IN_RESPONSE) {
				break;
			}
			validateIri(s.getSubject(), seen, errors);
			validateIri(s.getPredicate(), seen, errors);
			validateIri(s.getObject(), seen, errors);
			validateIri(s.getContext(), seen, errors);
		}
		if (!errors.isEmpty()) {
			throw new BadRequestException(String.join("; ", errors));
		}
	}

	private static void validateIri(Value value, Set<String> seen, Set<String> errors) {
		if (errors.size() >= MAX_ERRORS_IN_RESPONSE) {
			return;
		}
		String iriString = extractIriCandidate(value);
		if (iriString == null || !seen.add(iriString)) {
			return;
		}
		try {
			VF.createIRI(iriString);
		} catch (IllegalArgumentException e) {
			errors.add("Invalid IRI: " + StringUtils.abbreviate(iriString, MAX_IRI_LENGTH_IN_ERROR));
		}
	}

	private static String extractIriCandidate(Value value) {
		return switch (value) {
			case IRI iri -> iri.stringValue();
			case Literal lit -> {
				String label = lit.getLabel();
				yield (label.startsWith("http://") || label.startsWith("https://")) ? label : null;
			}
			case null, default -> null;
		};
	}

	private static void assertWritableToTripleStore(Model graph) {
		Repository repo = new SailRepository(new MemoryStore());
		try {
			repo.init();
			try (RepositoryConnection rc = repo.getConnection()) {
				rc.add(graph);
			} catch (RepositoryException e) {
				throw new InternalServerErrorException("Validator failed to accept graph", e);
			}
		} finally {
			try {
				repo.shutDown();
			} catch (RepositoryException e) {
				log.warn("Failed to shut down validator MemoryStore", e);
			}
		}
	}

}
