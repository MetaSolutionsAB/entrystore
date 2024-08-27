package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.mapper.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

public class SingleTransaction {

	public static void runBenchmark(RepositoryConnection connection, List<Object> persons) {

		LogUtils.logType("POPULATE");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting populating the model at", start);

		Model model = ObjectMapper.populateModelWithPersons(persons);

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ending populating the model at", end);
		LogUtils.logTimeDifference("Populating the model took", start, end);

		insertToDatabase(connection, model);
	}

	private static void insertToDatabase(RepositoryConnection connection, Model model) {

		LogUtils.logType(" INSERT ");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting inserting to database at", start);

		connection.begin();
		connection.add(model);
		connection.commit();

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended inserting to database at", end);
		LogUtils.logTimeDifference("Inserting to database took", start, end);
	}
}
