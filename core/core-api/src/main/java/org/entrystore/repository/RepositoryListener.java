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

import java.util.EventListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements Runnable to be used with Executors to make asynchronous execution
 * possible. The event object needs to be set before the executor can call the
 * run() method, otherwise the listener does not get any information about the
 * event.
 * 
 * @author Hannes Ebner
 */
public abstract class RepositoryListener implements Runnable, EventListener {
	
	private static Logger log = LoggerFactory.getLogger(RepositoryListener.class);
	
	RepositoryEventObject eventObject;

	abstract public void repositoryUpdated(RepositoryEventObject eventObject);
	
	public void setRepositoryEventObject(RepositoryEventObject eventObject) {
		this.eventObject = eventObject;
	}
	
	public RepositoryEventObject getRepositoryEventObject() {
		return eventObject;
	}
	
	public void run() {
		if (eventObject != null) {
			repositoryUpdated(eventObject);
			setRepositoryEventObject(null);
		} else {
			log.warn("Listener dispatched without event");
		}
	}

}