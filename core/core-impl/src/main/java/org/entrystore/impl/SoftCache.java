/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Soft-reference cache for Entry objects, mapping entry URIs to entries and maintaining a reverse
 * index from resource/metadata/relation URIs back to entry URIs.
 *
 * <p>Concurrency model: each ConcurrentHashMap operation is individually atomic, but compound
 * operations spanning both maps (cache + uri2entryURIs) are NOT atomic as a group. This is
 * acceptable because callers handle cache misses gracefully (fall back to repository lookup),
 * and same-entry put()/remove() never races in practice (they are distinct lifecycle events).
 *
 * <p>Copy-on-write in push()/pop(): the merge/computeIfPresent remapping functions create a new
 * Set rather than mutating the existing one. This is required because (1) the ConcurrentHashMap
 * contract states remapping functions should be side-effect-free as they may be re-applied under
 * contention, and (2) getByURI() reads the Set outside any lock — mutating in place could expose
 * a partially-updated Set to concurrent readers.
 */
@Slf4j
public class SoftCache {

	private final ConcurrentHashMap<URI, SoftReference<Entry>> cache = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<URI, Object> uri2entryURIs = new ConcurrentHashMap<>();

	private final Thread remover;
	private final ReferenceQueue<Entry> clearedRefs;

	@Getter
	private volatile boolean shutdown = false;

	public SoftCache() {
		clearedRefs = new ReferenceQueue<>();

		// start thread to delete cleared references from the cache
		remover = new Remover(clearedRefs, this);
		remover.start();

		// add a shutdown hook to interrupt the endless loop
		// the hook is only called when the whole VM is shutdown
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
	}

	int cacheSize() {
		return cache.size();
	}

	public void clear() {
		cache.clear();
		uri2entryURIs.clear();
	}

	public void put(Entry entry) {
		if (entry == null) return;
		URI entryURI = entry.getEntryURI();
		cache.put(entryURI, new EntrySoftReference(entry, clearedRefs));
		push(entry.getLocalMetadataURI(), entryURI);
		push(entry.getExternalMetadataURI(), entryURI);
		push(entry.getResourceURI(), entryURI);
		push(entry.getRelationURI(), entryURI);
	}

	@SuppressWarnings("unchecked")
	private void push(URI from, URI to) {
		if (from == null || to == null) {
			return;
		}
		uri2entryURIs.merge(from, to, (existing, newVal) -> {
			if (existing.equals(newVal)) {
				return existing;
			}
			Set<URI> set = new HashSet<>();
			if (existing instanceof Set) {
				set.addAll((Set<URI>) existing);
			} else {
				set.add((URI) existing);
			}
			set.add((URI) newVal);
			return set;
		});
	}

	@SuppressWarnings("unchecked")
	private void pop(URI from, URI to) {
		if (from == null || to == null) {
			return;
		}
		uri2entryURIs.computeIfPresent(from, (_, existing) -> {
			if (existing instanceof Set) {
				Set<URI> newSet = new HashSet<>((Set<URI>) existing);
				newSet.remove(to);
				return newSet.isEmpty() ? null : newSet;
			}
			return existing.equals(to) ? null : existing;
		});
	}

	public void remove(Entry entry) {
		if (entry == null) return;
		URI entryURI = entry.getEntryURI();
		cache.remove(entryURI);
		pop(entry.getLocalMetadataURI(), entryURI);
		pop(entry.getExternalMetadataURI(), entryURI);
		pop(entry.getResourceURI(), entryURI);
		pop(entry.getRelationURI(), entryURI);
	}

	public Entry getByEntryURI(URI uri) {
		if (uri == null) {
			return null;
		}
		SoftReference<Entry> sr = cache.get(uri);
		if (sr != null) {
			return sr.get();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public Set<Entry> getByURI(URI uri) {
		if (uri == null) {
			return null;
		}
		Object entryUris = uri2entryURIs.get(uri);
		if (entryUris != null) {
			HashSet<Entry> entries = new HashSet<>();
			if (entryUris instanceof Set) {
				for (URI entryURI : ((Set<URI>) entryUris)) {
					Entry entry = getByEntryURI(entryURI);
					if (entry != null) {
						entries.add(entry);
					}
				}
			} else {
				Entry entry = getByEntryURI((URI) entryUris);
				if (entry != null) {
					entries.add(entry);
				}
			}
			return entries.isEmpty() ? null : entries;
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

	private static class EntrySoftReference extends SoftReference<Entry> {

		final URI entryURI;
		final URI localMetadataURI;
		final URI externalMetadataURI;
		final URI resourceURI;
		final URI relationURI;

		EntrySoftReference(Entry entry, ReferenceQueue<Entry> queue) {
			super(entry, queue);
			this.entryURI = entry.getEntryURI();
			this.localMetadataURI = entry.getLocalMetadataURI();
			this.externalMetadataURI = entry.getExternalMetadataURI();
			this.resourceURI = entry.getResourceURI();
			this.relationURI = entry.getRelationURI();
		}
	}

	private class Remover extends Thread {

		private final ReferenceQueue<Entry> refQ;
		private final SoftCache softCache;

		public Remover(ReferenceQueue<Entry> rq, SoftCache softCache) {
			super();
			this.refQ = rq;
			this.softCache = softCache;
			setDaemon(true);
		}

		public void run() {
			try {
				while (!shutdown) {
					Reference<? extends Entry> ref = refQ.remove();
					try {
						if (ref instanceof EntrySoftReference esr) {
							if (softCache.cache.remove(esr.entryURI, esr)) {
								softCache.pop(esr.localMetadataURI, esr.entryURI);
								softCache.pop(esr.externalMetadataURI, esr.entryURI);
								softCache.pop(esr.resourceURI, esr.entryURI);
								softCache.pop(esr.relationURI, esr.entryURI);
							}
						} else {
							log.warn("Unexpected reference type dequeued from SoftCache: {}", ref.getClass().getName());
						}
					} catch (RuntimeException e) {
						URI failedURI = (ref instanceof EntrySoftReference esr2) ? esr2.entryURI : null;
						log.error("Error cleaning up soft reference for entry [{}], continuing", failedURI, e);
					}
				}
			} catch (InterruptedException e) {
				log.info("SoftCache remover got interrupted, shutting down");
			}
		}

	}
}
