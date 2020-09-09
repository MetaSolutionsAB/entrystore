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

/**
 * FIXME
 * this interface needs some work as it was created ad-hoc to fix some refactoring problems;
 * the Solr implementation contains too many Solr-specific parameters in the method signatures
 *
 * @author Hannes Ebner
 */
public interface SearchIndex {

	public void shutdown();

	public void reindex(boolean purgeAllBeforeReindex);

	public void reindex(URI contextURI, boolean purgeAllBeforeReindex);

	public void postEntry(Entry entry);

	public void removeEntry(Entry entry);

	public boolean isIndexing();

	public boolean isIndexing(URI contextURI);

}