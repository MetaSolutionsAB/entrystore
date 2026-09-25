/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;

/**
 * Manages EntryStore instance(s) as preparation for the tests in entrystore-core-impl.
 *
 * @author Hannes Ebner
 */
public abstract class AbstractCoreTest {

	public RepositoryManagerImpl rm;
	public ContextManager cm;
	public PrincipalManager pm;

	private void setUpEnvironment() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.SOLR, "off");
		//config.setProperty(Settings.SOLR_REINDEX_ON_STARTUP, "off");
		//config.setProperty(Settings.SOLR_URL, "/tmp/entrystore-test-solr/");

		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
	}

	@BeforeEach
	public void setUp() {
		setUpEnvironment();
		TestSuite.initDisneySuite(rm);
	}

	public void setUpWithoutSuite() {
		setUpEnvironment();
	}

	@AfterEach
	public void tearDown() {
		rm.shutdown();
		rm = null;
	}

	/**
	 * Replaces the external metadata URI of an entry directly in the store, bypassing its validation, as data
	 * stored before the validation existed.
	 */
	protected void replaceExternalMetadataInStore(Entry entry, URI externalMetadataURI) {
		ValueFactory vf = rm.getValueFactory();
		IRI entryIRI = vf.createIRI(entry.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove(entryIRI, RepositoryProperties.externalMetadata, null, entryIRI);
			rc.add(entryIRI, RepositoryProperties.externalMetadata, vf.createIRI(externalMetadataURI.toString()),
					entryIRI);
		}
	}

	/**
	 * Removes the statements with the given predicate from an entry graph in the store, bypassing the API, e.g. to
	 * make the entry corrupt.
	 */
	protected void removeFromEntryGraph(URI entryURI, IRI predicate) {
		IRI graph = SimpleValueFactory.getInstance().createIRI(entryURI.toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove((Resource) null, predicate, null, graph);
		}
	}

	/**
	 * Evicts an entry from the soft cache, so that it is loaded from the store again.
	 */
	protected static void evictFromSoftCache(Entry entry) {
		((ContextImpl) entry.getContext()).softCache.remove(entry);
	}

}
