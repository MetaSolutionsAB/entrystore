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

package org.entrystore.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Getter
@Setter
public class Arguments {
	String storeType;
	String baseUrl;
	int sizeToGenerate = 0;
	boolean isComplex = false;
	boolean withTransactions = false;
	boolean withInterRequests = false;
	int interRequestsModulo = -1;
	boolean withInterContexts = false;
	boolean withAcl = false;
	boolean batched = false;
	String indexes;
	Boolean forceSync;
	String solrUrl;
	boolean readAsGroupUser = false;
	int seededPrincipals = 0;
	int writers = 1;
	boolean reindex = false;
	boolean maintenance = false;
	int listBenchmark = 0;
	File storePath;
	File solrPath;

	public void setStorePath() throws IOException {
		Path path = Path.of(FileUtils.getTempDirectory().getAbsolutePath(), "benchmark-store-" + UUID.randomUUID());
		this.storePath = Files.createDirectories(path).toFile();
	}

	public void setStorePath(String storePath) {
		this.storePath = new File(storePath);
	}

	public void setSolrPath() throws IOException {
		Path path = Path.of(FileUtils.getTempDirectory().getAbsolutePath(), "benchmark-solr-" + UUID.randomUUID());
		this.solrPath = Files.createDirectories(path).toFile();
	}
}
