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


package org.entrystore.repository.impl;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.repository.Entry;


//TODO prioritize recently used objects so they are not
// garbage collected first. Simple use is to have hard references
// to everything used within the last 30 minutes, but that does 
// take into account amount of available memory.
public class SoftCache {
	
	HashMap<URI, SoftReference<Entry>> cache = new HashMap<URI, SoftReference<Entry>>();
	
	HashMap<URI, Object> uri2entryURIs = new HashMap<URI, Object>();
	
	Thread remover;
	
	ReferenceQueue<Entry> clearedRefs;
	
	Log log = LogFactory.getLog(SoftCache.class);
	
	boolean shutdown = false;
	
	public SoftCache() {
		clearedRefs = new ReferenceQueue<Entry>();
		
		// start thread to delete cleared references from the cache
		remover = new Remover(clearedRefs, this);
		remover.start();
		
		// add a shutdown hook to interrupt the endless loop
		// the hook is only called when the whole VM is shutdown
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				shutdown();
			}
		});
	}
	
	public void clear() {
		cache.clear();
		uri2entryURIs.clear();
	}

	public void put(Entry entry) {
		synchronized (cache) {
			URI entryURI = entry.getEntryURI();
			cache.put(entryURI, new SoftReference(entry, clearedRefs));
			push(entry.getLocalMetadataURI(), entryURI);
			push(entry.getExternalMetadataURI(), entryURI);
			push(entry.getResourceURI(), entryURI);
			push(entry.getRelationURI(), entryURI); 
		} 
	}

	private void push(URI from, URI to) {
		if (from == null || to == null) {
			return;
		}
		Object existingTo = uri2entryURIs.get(from); 
		if (existingTo == null) {
			uri2entryURIs.put(from, to);
		} else {
			if (existingTo instanceof Set) {
				((Set<URI>) existingTo).add(to);
			} else {
				HashSet<URI> set = new HashSet<URI>();
				set.add((URI) existingTo);
				set.add(to);
				uri2entryURIs.put(from, set);
			}
		}
	}
	private void pop(URI from, URI to) {
		if (from == null || to == null) {
			return;
		}
		Object existingTo = uri2entryURIs.get(from); 
		if (existingTo != null) {
			if (existingTo instanceof Set) {
				((Set) existingTo).remove(to);
				if (((Set) existingTo).isEmpty()) {
					uri2entryURIs.remove(from);
				}
			} else if (existingTo.equals(to)){
				uri2entryURIs.remove(from);
			}
		}
	}

	public void remove(Entry entry) {
		if(entry == null) return; 
		synchronized (cache) {
			URI entryURI = entry.getEntryURI();
			cache.remove(entryURI);
			pop(entry.getLocalMetadataURI(), entryURI);
			pop(entry.getExternalMetadataURI(), entryURI);
			pop(entry.getResourceURI(), entryURI);
			pop(entry.getRelationURI(), entryURI); 
		}
	}

	public Entry getByEntryURI(URI uri) {
		SoftReference<Entry> sr = cache.get(uri);

		if (sr != null) {
			return sr.get();
		}
		return null;
	}

	public Set<Entry> getByURI(URI uri) {
		if (uri2entryURIs.containsKey(uri)) {
			HashSet<Entry> entries = new HashSet<Entry>();
			Object entryUris = uri2entryURIs.get(uri);
			if (entryUris instanceof Set) {
				for (URI entryURI : ((Set<URI>) entryUris)) {
					entries.add(getByEntryURI(entryURI));
				}
			} else {
				entries.add(getByEntryURI((URI) entryUris));
			}
			return entries;
		}
		return null;
	}
	
	public void shutdown() {
		if (remover == null || (shutdown && remover.isInterrupted())) {
			return;
		}
		log.info("Shutting down SoftCache");
		shutdown = true;
		remover.interrupt();
	}
	
	public boolean isShutdown() {
		return shutdown;
	}

	private class Remover extends Thread {
		
		ReferenceQueue<Entry> refQ;
		
		SoftCache cache;

		public Remover(ReferenceQueue<Entry> rq, SoftCache cache) {
			super();
			this.refQ = rq;
			this.cache = cache;
			setDaemon(true);
		}

		public void run() {
			try {
				while (!shutdown) {
					Reference ref = refQ.remove();
					cache.remove((Entry) ref.get());
				}
			} catch (InterruptedException e) {
				log.info("SoftCache remover got interrupted, shutting down");
			}
		}

	}

}