package org.entrystore;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

    private static final File NATIVE_PATH = new File("modules/benchmark/benchmark-rdf4j/src/main/resources/native_store");
    private static final File LMDB_PATH = new File("modules/benchmark/benchmark-rdf4j/src/main/resources/lmdb_store");
    private static final String NATIVE = "native";
    private static final String MEMORY = "memory";
    private static final String LMDB = "lmdb";

    private static List<FakePerson> generateData(int sizeToGenerate) {

        LogUtils.logType("GENERATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting generating data at", start);

        List<FakePerson> persons = FakeGenerator.createPersonList(sizeToGenerate);

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended generating data at", end);
        LogUtils.logTimeDifference("Generating data took", start, end);

        return persons;
    }

    public static void readFromDatabase(RepositoryConnection connection, int modulo) {

        LogUtils.logType(" READING");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting reading from database at", start);

        if (modulo > 0) {

            String namedGraph = BenchmarkOntology.NAMED_GRAPH_PREFIX + "" + (-modulo);
            IRI context = connection.getValueFactory().createIRI(namedGraph);

            try (RepositoryResult<Statement> result = connection.getStatements(null, null, null, context)) {

                for (Statement statement : result) {
                    System.out.printf("Database contains: %s\n", statement);
                }
            }
        } else {
            try (RepositoryResult<Statement> result = connection.getStatements(null, null, null)) {

                for (Statement statement : result) {
                    //System.out.printf("Database contains: %s\n", statement);
                }
            }
        }

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended reading from database at", end);
        LogUtils.logTimeDifference("Reading from database took", start, end);
    }

    @NotNull
    private static Repository getDatabase(String benchmarkType) {

        String tripleIndexes = "cspo,spoc";

        // choose a storage type NATIVE | MEMORY
        return switch (benchmarkType) {
            case MEMORY -> new SailRepository(new MemoryStore());
            case NATIVE -> new SailRepository(new NativeStore(NATIVE_PATH, tripleIndexes));
            case LMDB -> new SailRepository(new LmdbStore(LMDB_PATH));
            case null, default -> throw new IllegalArgumentException();
        };
    }

    public static void main(String[] args) {

        try {

            // process arguments
            String benchmarkType = args[0];
            boolean withTransactions = "true".equals(args[1]);
            int sizeToGenerate = Integer.parseInt(args[2]);
            boolean withInterRequests = "true".equals(args[3]);
            int interRequestsModulo = -1;

            if (withInterRequests) {
                interRequestsModulo = Integer.parseInt(args[4]);
            }

            Repository database = getDatabase(benchmarkType);

            // welcome message
            LogUtils.logWelcome(benchmarkType, withTransactions, sizeToGenerate);

            // generate list of Persons with Addresses, 1 Person has exactly 1 Address
            List<FakePerson> persons = generateData(sizeToGenerate);

            // run either a multi-transaction or single-transaction benchmark
            try (RepositoryConnection connection = database.getConnection()) {

                if (withTransactions) {
                    MultipleTransactions.runBenchmark(connection, persons, interRequestsModulo);
                } else {
                    SingleTransaction.runBenchmark(connection, persons);
                }

                // read statements from database
                readFromDatabase(connection, interRequestsModulo);
            } finally {

                // close the connection and shutDown the database
                database.shutDown();
            }

            // benchmark finished, goodbye message
            LogUtils.logGoodbye();

        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            LogUtils.log.warn("No or bad arguments provided.");
        }
    }
}
