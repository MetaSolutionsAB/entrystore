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

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.config.Config;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.model.Arguments;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Benchmark {

	private static Config createConfiguration(Arguments arguments) {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, arguments.getStoreType());
		config.addProperty(Settings.STORE_PATH, "file:///" + arguments.getStorePath().getAbsolutePath().replace('\\', '/'));

		if (arguments.getStoreType().equalsIgnoreCase("native")) {
			String indexes = arguments.getIndexes() != null ? arguments.getIndexes() : BenchmarkCommons.INDEXES;
			config.addProperty(Settings.STORE_INDEXES, indexes);
			if (arguments.getForceSync() != null) {
				config.addProperty(Settings.STORE_FORCE_SYNC, arguments.getForceSync().toString());
			}
		}

		config.setProperty(Settings.BASE_URL, arguments.getBaseUrl());
		config.setProperty(Settings.SOLR, "off");

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

	/**
	 * Seeds the principals context with bystander users and groups (10 members per group) so that
	 * group resolution (PrincipalManagerImpl.getGroupUris - a scan over ALL principal entries)
	 * operates on a realistically sized directory instead of the handful of built-in principals.
	 * Seeded users get no secret (they never log in), which keeps seeding free of PBKDF2 cost.
	 */
	private static void seedPrincipals(RepositoryManagerImpl repositoryManager, int count) {
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		LogUtils.logType("  SEED  ");
		LocalDateTime start = LocalDateTime.now();

		List<User> users = new ArrayList<>(count);
		repositoryManager.inBatch(() -> {
			for (int i = 0; i < count; i++) {
				Entry userEntry = pm.createResource(null, GraphType.User, null, null);
				users.add((User) userEntry.getResource());
			}
		});

		int groupCount = Math.max(1, count / 10);
		List<Group> groups = new ArrayList<>(groupCount);
		repositoryManager.inBatch(() -> {
			for (int g = 0; g < groupCount; g++) {
				Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
				groups.add((Group) groupEntry.getResource());
			}
		});

		// Memberships outside the batches: ListImpl.addChild manages its own connection and must
		// see the committed user and group entries.
		for (int g = 0; g < groupCount; g++) {
			Group group = groups.get(g);
			for (int k = 0; k < 10; k++) {
				int userIndex = g * 10 + k;
				if (userIndex >= users.size()) {
					break;
				}
				group.addMember(users.get(userIndex));
			}
		}

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logTimeDifference("Seeding " + count + " users and " + groupCount + " groups took", start, end);
	}

	/**
	 * Reads every entry of the benchmark context as a non-admin user whose read access is granted
	 * only through group membership. This forces the group-resolution path in
	 * PrincipalManagerImpl.hasAccess/getGroupUris that admin and direct-grant reads bypass, so it
	 * measures the B-theme authorization findings.
	 */
	private static void readAllAsGroupUser(RepositoryManagerImpl repositoryManager, int sizeToGenerate) {
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		// A user that is NOT granted anything directly.
		Entry readerEntry = pm.createResource(null, GraphType.User, null, null);
		User reader = (User) readerEntry.getResource();
		pm.setPrincipalName(readerEntry.getResourceURI(), "Group Reader");
		reader.setSecret(BenchmarkCommons.BENCHMARK_USER_SECRET);

		// A group the user belongs to, granted ReadResource on the context (which grants read on all
		// its entries by inheritance).
		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		Group group = (Group) groupEntry.getResource();
		pm.setPrincipalName(groupEntry.getResourceURI(), "Reader Group");
		group.addMember(reader);

		Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1");
		context.getEntry().addAllowedPrincipalsFor(PrincipalManager.AccessProperty.ReadResource, group.getURI());

		pm.setAuthenticatedUserURI(reader.getURI());

		LogUtils.logType(" READING");
		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting reading as group-member user at", start);

		int read = 0;
		for (URI entryURI : context.getEntries()) {
			Entry entry = context.getByEntryURI(entryURI);
			try {
				entry.getMetadataGraph().objects();
				read++;
			} catch (Exception e) {
				// authorization or load failure — counted as skipped
			}
		}

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended reading as group-member user at", end);
		LogUtils.logTimeDifference("Reading as group-member user took", start, end);
		LogUtils.log.info("Read {} entries as group-member user", read);
	}

	private static void readAllFromRepository(RepositoryManagerImpl repositoryManager, int sizeToGenerate) {

		LogUtils.logType(" READING");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting reading from database at", start);

		try (RepositoryConnection connection = repositoryManager.getRepository().getConnection();
			 RepositoryResult<Statement> result = connection.getStatements(null, null, null)) {
			for (Statement statement : result) {
				String value = statement.getObject().stringValue();
				if (sizeToGenerate < 11) {
					System.out.printf("Database contains: %s\n", statement);
				}
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
			RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(arguments.getBaseUrl(), configuration);

			// turn acl off or use admin
			if (!arguments.isWithAcl()) {
				repositoryManager.setCheckForAuthorization(false);
			} else {
				repositoryManager.getPrincipalManager().setAuthenticatedUserURI(repositoryManager.getPrincipalManager().getAdminUser().getURI());
			}

			List<Object> persons = generateData(arguments.getSizeToGenerate(), arguments.isComplex());

			try {

				if (arguments.isWithTransactions()) {
					if (arguments.getWriters() > 1) {
						ConcurrentMultipleTransactions.runBenchmark(repositoryManager, persons, arguments.getWriters(), arguments.isBatched(), arguments.isWithAcl());
					} else if (arguments.isBatched()) {
						MultipleTransactionsBatched.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());
					} else {
						MultipleTransactions.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());
					}

					if (arguments.isMaintenance() && !arguments.isWithInterContexts()) {
						MaintenancePhase.run(repositoryManager);
					}

					// reading
					if (!arguments.isWithInterContexts()) {
						Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1");
						readAllFromDatabase(context, arguments.getSizeToGenerate());
						if (arguments.isReadAsGroupUser() && arguments.isWithAcl()) {
							if (arguments.getSeededPrincipals() > 0) {
								seedPrincipals(repositoryManager, arguments.getSeededPrincipals());
							}
							readAllAsGroupUser(repositoryManager, arguments.getSizeToGenerate());
						}
					}
				} else {
					SingleTransaction.runBenchmark(repositoryManager, persons);
					readAllFromRepository(repositoryManager, arguments.getSizeToGenerate());
				}
			} finally {
				// close the connection and shutDown the database
				repositoryManager.shutdown();
			}

			// benchmark finished, goodbye message
			LogUtils.logGoodbye();

		} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | IOException ex) {
			LogUtils.log.error("No or bad arguments provided.");
			LogUtils.log.error(ex.getMessage());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			LogUtils.log.error("Benchmark interrupted.", ex);
		}
	}
}
