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
import org.apache.commons.io.FileUtils;
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
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
public class ValidatorService {

	private static final ValueFactory VF = new ValidatingValueFactory();

	public void validate(String rdfBody, String mediaType) {
		Model graph = GraphUtil.deserializeGraph(rdfBody, mediaType);
		assertAllIrisValid(graph);
		assertWritableToNativeStore(graph);
	}

	private static void assertAllIrisValid(Model graph) {
		Set<String> errors = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		for (Statement s : graph) {
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
		String iriString = extractIriCandidate(value);
		if (iriString == null || !seen.add(iriString)) {
			return;
		}
		try {
			VF.createIRI(iriString);
		} catch (IllegalArgumentException e) {
			errors.add("Invalid IRI: " + iriString);
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

	private static void assertWritableToNativeStore(Model graph) {
		Path tmpPath;
		try {
			tmpPath = Files.createTempDirectory("entrystore-validator-");
		} catch (IOException e) {
			throw new InternalServerErrorException("Validator temporary storage unavailable", e);
		}

		Repository repo = null;
		try {
			repo = new SailRepository(new NativeStore(tmpPath.toFile()));
			try {
				repo.init();
			} catch (RepositoryException e) {
				throw new InternalServerErrorException("Failed to initialize validator store", e);
			}
			try (RepositoryConnection rc = repo.getConnection()) {
				rc.add(graph);
			} catch (RepositoryException e) {
				throw new BadRequestException("Failed to store graph for validation", e);
			}
		} finally {
			if (repo != null) {
				try {
					repo.shutDown();
				} catch (RepositoryException e) {
					log.warn("Failed to shut down validator NativeStore at {}", tmpPath, e);
				}
			}
			if (!FileUtils.deleteQuietly(tmpPath.toFile())) {
				log.warn("Failed to delete validator temp dir {}", tmpPath);
			}
		}
	}

}
