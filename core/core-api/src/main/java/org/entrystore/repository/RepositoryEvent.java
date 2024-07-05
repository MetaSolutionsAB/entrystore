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

/**
 * @author Hannes Ebner
 */
public enum RepositoryEvent {

	All,
	EntryCreated,
	EntryUpdated, // TODO event firing only implemented for EntryImpl.setGraph()
	EntryDeleted,
	EntryAclGuestUpdated, // implemented for EntryImpl.setGraph() and changes concerning _guest,
						  // NOT for EntryImpl.updateAllowedPrincipalsFor() which only seems to be
						  // used during entry creation
	EntryProjectTypeUpdated,
	MetadataUpdated,
	ExternalMetadataUpdated,
	ExtractedMetadataUpdated, // TODO event firing not implemented yet
	ResourceUpdated, // TODO partially implemented for ListImpl, fully implemented for DataImpl
	ResourceDeleted // TODO partially implemented for ListImpl, fully implemented for DataImpl

}