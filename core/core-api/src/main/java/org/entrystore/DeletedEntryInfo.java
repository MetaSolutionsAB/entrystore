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

import java.net.URI;
import java.util.Date;

/**
 * @author Hannes Ebner
 */
public class DeletedEntryInfo {
	
	private URI entryURI;
	
	private Date deletionDate;
	
	private URI deletedBy;
	
	public DeletedEntryInfo(URI entryURI, Date deletionDate, URI deletedBy) {
		this.entryURI = entryURI;
		this.deletionDate = deletionDate;
		this.deletedBy = deletedBy;
	}

	public Date getDeletionDate() {
		return deletionDate;
	}

	public void setDeletionDate(Date deletionDate) {
		this.deletionDate = deletionDate;
	}

	public URI getDeletedBy() {
		return deletedBy;
	}

	public void setDeletedBy(URI deletedBy) {
		this.deletedBy = deletedBy;
	}
	
	public URI getEntryURI() {
		return entryURI;
	}

	public void setEntryURI(URI entryURI) {
		this.entryURI = entryURI;
	}
	
	public String toString() {
		return "[" + entryURI + ", " + deletionDate + ", " + deletedBy + "]";
	}

}