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

package se.kmr.scam.harvester.factory;

import java.net.URI;

import org.entrystore.repository.Entry;
import org.entrystore.repository.impl.RepositoryManagerImpl;

import se.kmr.scam.harvester.Harvester;

public interface HarvesterFactory {
	
	public Harvester createHarvester(String target, String metadataType, String set, String timeRegExp, RepositoryManagerImpl rm, URI ownerContextURI) throws HarvesterFactoryException; 
	
	public Harvester getHarvester(RepositoryManagerImpl rm, URI ownerContextURI) throws HarvesterFactoryException;
	
	public void deleteHarvester(Entry contextEntry);

}