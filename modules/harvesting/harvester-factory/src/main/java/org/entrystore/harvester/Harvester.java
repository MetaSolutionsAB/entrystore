/**
 * Copyright (c) 2007-2010
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

package org.entrystore.harvester;

import java.net.URI;

import org.entrystore.repository.impl.RepositoryManagerImpl;



public abstract class Harvester {
	protected String metadataType; 
	protected String name;
	protected URI ownerContextURI; 
	protected String timeRegExp; 
	protected String target; 
	protected RepositoryManagerImpl rm; 
	protected String from;
	protected String until;  
	protected String set;

	public Harvester(String name, String target, String metadataType, String set, String timeRegExp, RepositoryManagerImpl rm, URI ownerContextURI) {
		this.name = name;
		this.target = target; 
		this.metadataType = metadataType; 
		this.ownerContextURI = ownerContextURI; 
		this.timeRegExp = timeRegExp; 
		this.rm = rm; 
		this.from = null;
		this.until = null;  
		this.set = set;
	}
	
	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	public String getUntil() {
		return until;
	}

	public void setUntil(String until) {
		this.until = until;
	}

	public String getSet() {
		return set;
	}

	public void setSet(String set) {
		this.set = set;
	}

	public RepositoryManagerImpl getRM() {
		return rm;
	}
	
	public String getTimeRegExp() {
		return timeRegExp;
	}

	public URI getOwnerContextURI() {
		return ownerContextURI;
	}

	public void setOwnerContextURI(URI ownerContextURI) {
		this.ownerContextURI = ownerContextURI;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
	
	public String getMetadataType() {
		return metadataType;
	}
	
	public String getTarget() {
		return target;
	}

	public void setTarget(String target) {
		this.target = target;
	}

	public void setMetadataType(String metadataType) {
		this.metadataType = metadataType;
	}
	
	public abstract void run();
	
	public abstract void run(String identifier); 
	
//	public abstract void stop();
	
//	public abstract void start();
	
	public abstract boolean delete();
	
	public abstract void setTimeRegExp(String timeRegExp);
		
}