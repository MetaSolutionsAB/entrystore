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

package org.entrystore.repository.util;

import java.util.Set;

import org.entrystore.repository.Entry;


public class QueryResult {
	
	private Set<Entry> entries;
	
	private long hits;
	
	public QueryResult(Set<Entry> entries, long hits) {
		this.entries = entries;
		this.hits = hits;
	}
	
	public Set<Entry> getEntries() {
		return entries;
	}
	
	public long getHits() {
		return hits;
	}
	
}