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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModelUtilTest {

	private static final ValueFactory VF = SimpleValueFactory.getInstance();
	private static final IRI A = VF.createIRI("http://example.com/a");
	private static final IRI B = VF.createIRI("http://example.com/b");
	private static final IRI OTHER = VF.createIRI("http://example.com/other");
	private static final IRI P = VF.createIRI("http://example.com/p");
	private static final IRI CONTEXT = VF.createIRI("http://example.com/graph");

	@Test
	public void replaceSubject_rewritesTheSubjectAndLeavesAMatchingObjectAlone() {
		Model graph = new LinkedHashModel();
		graph.add(A, P, OTHER);
		graph.add(OTHER, P, A);

		Model result = ModelUtil.replaceSubject(graph, A, B);

		assertTrue(result.contains(B, P, OTHER));
		assertTrue(result.contains(OTHER, P, A));
		assertEquals(2, result.size());
	}

	@Test
	public void replaceIRI_rewritesASelfReferenceInBothPositions() {
		Model graph = new LinkedHashModel();
		graph.add(A, P, A);

		Model result = ModelUtil.replaceIRI(graph, A, B);

		assertTrue(result.contains(B, P, B));
		assertFalse(result.contains(A, null, null));
		assertFalse(result.contains(null, null, A));
	}

	@Test
	public void replaceIRI_rewritesThePredicateToo() {
		Model graph = new LinkedHashModel();
		graph.add(OTHER, A, OTHER);

		Model result = ModelUtil.replaceIRI(graph, A, B);

		assertTrue(result.contains(OTHER, B, OTHER));
	}

	@Test
	public void replaceIRIs_appliesEveryReplacementInOnePass() {
		Model graph = new LinkedHashModel();
		graph.add(A, P, B);

		// swapping in one pass must not chase A -> B -> A
		Model result = ModelUtil.replaceIRIs(graph, Map.of(A, B, B, A));

		assertTrue(result.contains(B, P, A));
		assertEquals(1, result.size());
	}

	@Test
	public void leavesLiteralsAndOtherIRIsUntouched() {
		Model graph = new LinkedHashModel();
		graph.add(A, P, VF.createLiteral("a label"));
		graph.add(OTHER, P, OTHER);

		Model result = ModelUtil.replaceIRI(graph, A, B);

		assertTrue(result.contains(B, P, VF.createLiteral("a label")));
		assertTrue(result.contains(OTHER, P, OTHER));
	}

	@Test
	public void preservesTheContextOfEveryStatement() {
		Model graph = new LinkedHashModel();
		graph.add(A, P, OTHER, CONTEXT);
		graph.add(OTHER, P, OTHER, CONTEXT);

		Model result = ModelUtil.replaceSubject(graph, A, B);

		assertTrue(result.contains(B, P, OTHER, CONTEXT));
		assertTrue(result.contains(OTHER, P, OTHER, CONTEXT));
	}

	@Test
	public void emptyModelStaysEmpty() {
		assertTrue(ModelUtil.replaceIRI(new LinkedHashModel(), A, B).isEmpty());
	}
}
