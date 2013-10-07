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