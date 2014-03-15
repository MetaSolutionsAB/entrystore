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

/**
 * EntryType specifies which parts of an {@link Entry}, more specifically the
 * {@link Metadata} and the {@link Resource}, that are maintained in the {@link Context}
 * of the entry, i.e. the Context you get by calling {@link Entry#getContext()}.
 * 
 * @author Matthias Palmér
 */
public enum EntryType {
	
	/**
	 * Reference means means that both the Resource and the Metadata for 
	 * the resource is maintained outside of the entry's context.
	 * 
	 * Note that the repository may have cached the remote metadata
	 * within the entry's context though.
	 */
	Reference,
	
	/**
	 * LinkReference means that:
	 * <ul><li> the Resource is maintained outside of 
	 * the entry's context</li>
	 * <li> there are external metadata that might be cached in the context</li>
	 * <li> there are complimentary metadata that are kept locally</li></ul>
	 * 
	 * Hence, a LinkReference behaves as both a Link and a Reference.
	 * 
	 */	
	LinkReference,
	
	/**
	 * Link says that the Metadata are maintained in the entry's context.
	 * The resource itself is maintained outside of the entry's context though.
	 */
	Link,
	
	/**
	 * Local means that both Metadata and Resource is maintained in the entry's
	 * context.
	 */
	Local

}