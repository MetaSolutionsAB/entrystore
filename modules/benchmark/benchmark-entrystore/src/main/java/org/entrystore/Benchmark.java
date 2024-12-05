package org.entrystore;

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
			config.addProperty(Settings.STORE_INDEXES, BenchmarkCommons.INDEXES);
		}

		config.setProperty(Settings.BASE_URL, arguments.getBaseUrl());
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
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

				MultipleTransactions.runBenchmark(repositoryManager, persons, arguments.getInterRequestsModulo(), arguments.isWithInterContexts(), arguments.isWithAcl());

				// reading
				if (!arguments.isWithInterContexts()) {
					Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS + "_0");
					readAllFromDatabase(context, arguments.getSizeToGenerate());
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
