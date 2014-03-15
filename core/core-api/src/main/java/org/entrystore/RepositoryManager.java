/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

package org.entrystore;

import java.net.URL;

import net.sf.ehcache.CacheManager;

import org.entrystore.repository.util.SolrSupport;
import org.openrdf.model.ValueFactory;



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
	 * @return the {@link PrincipalManager} which is the center of managing
	 * all users, groups, and access control.
	 */
	PrincipalManager getPrincipalManager();
	
	/**
	 * @return the {@link ContextManager} which is the center for managing
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

	public boolean isCheckForAuthorization();
	
	public void shutdown();

	boolean hasModificationLockOut();

	void setModificationLockOut(boolean lockout);

	Config getConfiguration();
	
	/**
	 * @return A CacheManager instance. May be null if the disk cache is deactivated.
	 */
	CacheManager getCacheManager();
	
	boolean hasQuotas();
	
	long getDefaultQuota();
	
	void registerListener(RepositoryListener listener, RepositoryEvent event);
	
	void unregisterListener(RepositoryListener listener, RepositoryEvent event);
	
	void fireRepositoryEvent(RepositoryEventObject eventObject);
	
	SolrSupport getSolrSupport();
	
	ValueFactory getValueFactory();
	
}