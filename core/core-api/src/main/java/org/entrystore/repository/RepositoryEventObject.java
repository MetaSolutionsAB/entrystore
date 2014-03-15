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

package org.entrystore.repository;

import java.util.EventObject;

import org.entrystore.Entry;
import org.openrdf.model.Graph;

/**
 * @author Hannes Ebner
 */
public class RepositoryEventObject extends EventObject {
	
	RepositoryEvent event;
	
	Graph updatedGraph;

	public RepositoryEventObject(Entry source, RepositoryEvent event) {
		super(source);
		this.event = event;
	}
	
	public RepositoryEventObject(Entry source, RepositoryEvent event, Graph updatedGraph) {
		this(source, event);
		this.updatedGraph = updatedGraph;
	}
	
	public RepositoryEvent getEvent() {
		return event;
	}

	/**
	 * @return Can be null. A graph is included directly in the event object to
	 *         save the getGraph() call on the Entry object, minimizing the
	 *         repository requests.
	 */
	public Graph getUpdatedGraph() {
		return updatedGraph;
	}
	
	@Override
	public String toString() {
		return new StringBuffer(event.name()).append(",").append(source.toString()).toString();
	}

}