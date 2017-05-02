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

import java.net.URI;
import java.util.Date;


/**
 * Each provenance entity corresponds to a change and contains information about
 * who made the change and when. Furthermore, each entity have a unique
 * URI as well as a provenance type.
 *
 * @author Matthias Palmér
 */
public interface Entity {
	/**
	 * @return a unique URI for this entity
	 */
	URI getURI();

	/**
	 * @return the date when this activity took place
	 */
	Date getGeneratedDate();

	/**
	 * A reference to the user object who made the change.
	 *
	 * @return a resourceURI of the user (never a group).
	 */
	URI getAttributedURI();

	/**
	 * @return the type of this entity
	 */
	ProvenanceType getProvenanceType();
}