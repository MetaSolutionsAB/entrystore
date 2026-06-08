package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.repository.RepositoryManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Baseline benchmark that bypasses the EntryStore Entry / Context / MetadataImpl layers
 * and writes the entire person dataset to the underlying RDF4J repository in a single
 * transaction.
 * <p>
 * The result is the lower bound on insert time for this dataset on the current store
 * configuration: no per-entry connection churn, no per-entry fsync, no inverse-relation
 * lookups. Comparing this against {@link MultipleTransactions} isolates EntryStore overhead
 * (one commit per createResource and per setGraph) from the raw store cost.
 */
public class SingleTransaction {

	public static void runBenchmark(RepositoryManager repositoryManager, List<Object> persons) {

		LogUtils.logType("POPULATE");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting populating the model at", start);

		Model model = ObjectMapper.populateModelWithPersons(persons);

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ending populating the model at", end);
		LogUtils.logTimeDifference("Populating the model took", start, end);

		insertToDatabase(repositoryManager.getRepository(), model);
	}

	private static void insertToDatabase(Repository repository, Model model) {

		LogUtils.logType(" INSERT ");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting inserting to database at", start);

		try (RepositoryConnection connection = repository.getConnection()) {
			connection.begin();
			connection.add(model);
			connection.commit();
		}

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended inserting to database at", end);
		LogUtils.logTimeDifference("Inserting to database took", start, end);
	}
}
