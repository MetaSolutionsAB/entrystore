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

package org.entrystore;

import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.net.URI;


/**
 * This class holds a resource that have a digital representation 
 * in the repository. If the digital representation is in a free form
 * such as a text document, an image etc. use the sub-interface 
 * {@link Data} instead. Otherwise, if the digital representation has a 
 * structure which is known by the repository, a data-structure 
 * is maintained in the repository and accessed via specific methods.
 * This is the case for all {@link EntryType#Local} {@link EntryType}s
 * with a {@link GraphType}s that is not {@link GraphType#None}.
 * Examples include {@link List}, {@link Context}, {@link ContextManager}, {@link User}, 
 * {@link Group} and {@link PrincipalManager}.
 * 
 * @author matthias
 */
public interface Resource {

	/**
	 * @return the URI of the resource.
	 */
	URI getURI();
	Entry getEntry();
	void remove(RepositoryConnection rc) throws Exception;
	boolean isRemovable();
}