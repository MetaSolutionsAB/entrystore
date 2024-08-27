package org.entrystore;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.model.Arguments;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

	@NotNull
	private static Repository getDatabase(Arguments arguments) {

		// choose a storage type NATIVE | MEMORY
		return switch (arguments.getStoreType()) {
			case BenchmarkCommons.MEMORY -> new SailRepository(new MemoryStore());
			case BenchmarkCommons.NATIVE ->
					new SailRepository(new NativeStore(arguments.getStorePath(), BenchmarkCommons.INDEXES));
			case BenchmarkCommons.LMDB -> new SailRepository(new LmdbStore(arguments.getStorePath()));
			case null, default -> throw new IllegalArgumentException("Not a valid storage type provided.");
		};
	}

	private static List<Object> generateData(int sizeToGenerate, boolean isComplex) {

		LogUtils.logType("GENERATE");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting generating data at", start);

		List<Object> objects = ObjectGenerator.createPersonList(sizeToGenerate, isComplex);

		LocalDateTime end = LocalDateTime.now();
		LogUtils.logDate("Ended generating data at", end);
		LogUtils.logTimeDifference("Generating data took", start, end);

		return objects;
	}

	private static void readAllFromDatabase(RepositoryConnection connection, int sizeToGenerate) {

		LogUtils.logType(" READING");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting reading from database at", start);

		try (RepositoryResult<Statement> result = connection.getStatements(null, null, null)) {
			String foo;

			for (Statement statement : result) {
				foo = statement.getObject().stringValue();

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

			// get the Repository instance based on store type
			Repository database = getDatabase(arguments);

			// generate list of objects
			List<Object> objects = generateData(arguments.getSizeToGenerate(), arguments.isComplex());

			// run either a multi-transaction or single-transaction benchmark
			try (RepositoryConnection connection = database.getConnection()) {

				if (arguments.isWithTransactions()) {
					MultipleTransactions.runBenchmark(connection, objects, arguments.getInterRequestsModulo());
				} else {
					SingleTransaction.runBenchmark(connection, objects);
				}

				// read statements from database
				readAllFromDatabase(connection, arguments.getSizeToGenerate());
			} finally {
				// close the connection and shutDown the database
				database.shutDown();
			}

			// benchmark finished, goodbye message
			LogUtils.logGoodbye();

		} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | IOException ex) {
			LogUtils.log.error("No or bad arguments provided.");
			LogUtils.log.error(ex.getMessage());
		}
	}
}
