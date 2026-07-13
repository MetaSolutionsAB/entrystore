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
					if (arguments.isBatched()) {
						MultipleTransactionsBatched.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());
					} else {
						MultipleTransactions.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());
					}

					// reading
					if (!arguments.isWithInterContexts()) {
						Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1");
						readAllFromDatabase(context, arguments.getSizeToGenerate());
						if (arguments.isReadAsGroupUser() && arguments.isWithAcl()) {
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
		}
	}
}
