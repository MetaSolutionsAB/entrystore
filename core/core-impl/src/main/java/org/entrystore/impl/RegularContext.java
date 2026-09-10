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

package org.entrystore.impl;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;

import java.net.URI;

/**
 * @author Matthias Palmér
 */
public class RegularContext extends ContextImpl {

	/**
	 * Creates a principal manager
	 * @param entry this principal managers entry
	 * @param uri this principal managers URI 
	 * @param cache
	 */
	public RegularContext(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri, cache);
	}

	@Override
	public Entry createResource(String entryId, GraphType buiType, ResourceType repType, URI listURI) {
		return switch (buiType) {
			case List, ResultList, Graph, String, None, Pipeline, PipelineResult ->
				super.createResource(entryId, buiType, repType, listURI);
			// understates the whitelist above, but it reaches the client and an IT asserts it verbatim
			default -> throw new IllegalArgumentException(
				"Regular context only support Lists, ResultLists and None as BuiltinTypes");
		};
	}

}
