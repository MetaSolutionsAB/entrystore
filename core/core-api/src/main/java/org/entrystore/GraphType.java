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

/**
 * Entries with a set GraphType get special treatment in EntryStore.
 * 
 * @author Matthias Palmér
 * @author Hannes Ebner
 * @author Eric Johansson
 */
public enum GraphType {

	/**
	 * Special container resource which keeps track of a set of entries 
	 * (resources with corresponding meta- and metametadata) that should be 
	 * managed together, at a minimum it provides default ownership of the entries.
	 * In portfolio applications a Context corresponds to a single portfolio.
	 *
	 * @see org.entrystore.Context
	 */
	Context,

	/**
	 * A context that has a special meaning in the current context, 
	 * examples include {@link ContextManager} and {@link PrincipalManager}.
	 *
	 * @see org.entrystore.Context
	 */
	SystemContext,

	/**
	 * A user or group used in access control lists for managing access to 
	 * various entries.
	 *
	 * @see org.entrystore.User
	 */
	User,

	/**
	 * A user or group used in access control lists for managing access to 
	 * various entries.
	 *
	 * @see org.entrystore.Group
	 */
	Group,
	
	/**
	 * A ordered list of entry.
	 *
	 * @see org.entrystore.List
	 */
	List,
	
	/**
	 * A list that is dynamically generated.
	 *
	 * @see org.entrystore.List
	 */
	ResultList,

	/**
	 * A string, that should be used when you want to save a text string 
	 */
	String,
	
	/**
	 * A named graph
	 */
	Graph,

	/**
	 * A pipeline for data transformations
	 */
	Pipeline,

	/**
	 * Pipeline result for storing the result of data transformations
	 */
	PipelineResult,
	
	/**
	 * All other resource that have no specific treatment in the repository.
	 */
	None
	
}