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

package org.entrystore;

import org.apache.commons.io.FileUtils;
import org.entrystore.config.Config;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.model.Arguments;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

	private static Config createConfiguration(Arguments arguments) throws IOException {
		String solrUrl = arguments.getSolrUrl();
		if (solrUrl == null || !(solrUrl.startsWith("http://") || solrUrl.startsWith("https://"))) {
			throw new IllegalArgumentException("benchmark-solr requires -S/--solr-url pointing at an http(s) Solr core "
					+ "(EntryStore no longer supports embedded Solr). Example: -S http://localhost:8983/solr/entrystore-core");
		}

		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, arguments.getStoreType());
		config.addProperty(Settings.STORE_PATH, "file:///" + arguments.getStorePath().getAbsolutePath().replace('\\', '/'));

		if (arguments.getStoreType().equalsIgnoreCase("native")) {
			config.addProperty(Settings.STORE_INDEXES, BenchmarkCommons.INDEXES);
		}

		config.setProperty(Settings.BASE_URL, BenchmarkCommons.BASE_URL);
		config.setProperty(Settings.SOLR, "on");
		config.setProperty(Settings.SOLR_URL, solrUrl);

		return config;
	}

	private static List<Object> generateData(int sizeToGenerate, boolean isComplex) {

		LogUtils.logType("GENERATE");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting generating data at", start);

		List<Object> persons = ObjectGenerator.createPersonList(sizeToGenerate, isComplex);

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended generating data at", end);
		LogUtils.logTimeDifference("Generating data took", start, end);

		return persons;
	}

	private static void readAllFromDatabase(Context context, int sizeToGenerate) {

		LogUtils.logType(" READING");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting reading from database at", start);

		for (URI entryURI : context.getEntries()) {
			Entry entry = context.getByEntryURI(entryURI);
			try {
				String dump = entry.getResourceURI() + ": " + entry.getMetadataGraph().objects();
				if (sizeToGenerate < 11) {
					System.out.printf("Database contains: %s\n", dump);
				}
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended reading from database at", end);
		LogUtils.logTimeDifference("Reading from database took", start, end);
	}

	public static void main(String[] args) {
		try {

			Arguments arguments = BenchmarkCommons.processArguments(args);

			Config configuration = createConfiguration(arguments);
			RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(BenchmarkCommons.BASE_URL, configuration);

			// turn acl off or use admin
			if (!arguments.isWithAcl()) {
				repositoryManager.setCheckForAuthorization(false);
			} else {
				repositoryManager.getPrincipalManager().setAuthenticatedUserURI(repositoryManager.getPrincipalManager().getAdminUser().getURI());
			}

			List<Object> persons = generateData(arguments.getSizeToGenerate(), arguments.isComplex());

			try {

				MultipleTransactions.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());

				// reading
				if (!arguments.isWithInterContexts()) {
					Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS + "_0");
					readAllFromDatabase(context, arguments.getSizeToGenerate());
				}

			} finally {
				// close the connection and shutDown the database and solr
				repositoryManager.shutdown();
				FileUtils.deleteDirectory(arguments.getStorePath());
				FileUtils.deleteDirectory(arguments.getSolrPath());
			}

			// benchmark finished, goodbye message
			LogUtils.logGoodbye();

		} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | InterruptedException ex) {
			LogUtils.log.error("No or bad arguments provided.");
			LogUtils.log.error(ex.getMessage());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
