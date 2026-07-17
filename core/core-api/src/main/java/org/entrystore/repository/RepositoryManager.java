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

package org.entrystore.repository;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.SearchIndex;
import org.entrystore.config.Config;

import java.net.URL;



/**
 * This class is the central point for accessing a SCAM repository.
 * The RepositoryManager consists of:
 * <nl><li>ContextManager that manages all non system specific contexts.</li>
 * <li>PrincipalManager that manages all users and groups (principals),
 * used for expressing access control on contexts and resources.</li>
 * <li>Methods for managing administrative settings.</li></nl>
 *
 * @author matthias
 * @author Hannes Ebner
 */
public interface RepositoryManager {
	/**
	 * @return the {@link org.entrystore.PrincipalManager} which is the center of managing
	 * all users, groups, and access control.
	 */
	PrincipalManager getPrincipalManager();

	/**
	 * @return the {@link org.entrystore.ContextManager} which is the center for managing
	 * all non-system Contexts.
	 */
	ContextManager getContextManager();

	/**
	 * @return a list of alias (used also as entryIds) for the SystemContexts that are to be
	 * present in the current Repository.
	 */
	java.util.List<String> getSystemContextAliases();

	/**
	 * @param alias of a SystemContext.
	 * @return Class that should be initialized in ContextManager for the given alias.
	 */
	Class getSystemContextClassForAlias(String alias);

	/**
	 * @return class to use for all regular contexts.
	 */
	Class getRegularContextClass();

	/**
	 * @return the Repository URL from which this system is accessed.
	 */
	URL getRepositoryURL();

	/**
	 * @return The main repository.
	 */
	Repository getRepository();

	/**
	 * @return The provenance repository. May be null if instance is configured without provenance.
	 */
	Repository getProvenanceRepository();

	public boolean isCheckForAuthorization();

	public void shutdown();

	boolean hasModificationLockOut();

	void setModificationLockOut(boolean lockout);

	Config getConfiguration();

	boolean hasQuotas();

	long getDefaultQuota();

	long getMaximumFileSize();

	void registerListener(RepositoryListener listener, RepositoryEvent event);

	void unregisterListener(RepositoryListener listener, RepositoryEvent event);

	void fireRepositoryEvent(RepositoryEventObject eventObject);

	SearchIndex getIndex();

	ValueFactory getValueFactory();

	/**
	 * Runs {@code work} inside a single repository transaction. Batch-aware operations reuse
	 * this transaction's connection and defer their commit to the end of the batch: entry
	 * creation, metadata {@code setGraph}, and the entry-info/ACL setters. List and resource
	 * mutations such as {@code List.addChild}/{@code removeChild}/{@code setChildren} and the
	 * {@code User} setters still open their own connection and commit individually — on a
	 * rollback their changes remain committed.
	 * <p>
	 * The batch must stay on one thread: {@code work} must not fan repository mutations out to
	 * worker threads and wait for them (the batch thread holds the repository monitor, so the
	 * workers block in every setter while the batch waits on them — a permanent deadlock; to
	 * combine batching with concurrency, run one batch per worker thread).
	 * <p>
	 * On any throwable from {@code work} the batch is rolled back and the throwable is rethrown
	 * unchanged (ENTRYSTORE-1089 exposes this on the interface so bulk imports do not need to
	 * cast to the implementation).
	 */
	void inBatch(Runnable work);

}
