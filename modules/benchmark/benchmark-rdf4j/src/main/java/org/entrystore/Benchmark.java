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
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

    private static final File NATIVE_PATH = new File("./testdata/native_store");
    private static final File LMDB_PATH = new File("./testdata/lmdb_store");
    private static final String NATIVE = "native";
    private static final String MEMORY = "memory";
    private static final String LMDB = "lmdb";

    @NotNull
    private static Repository getDatabase(String benchmarkType) {

        String tripleIndexes = "cspo,spoc";

        // choose a storage type NATIVE | MEMORY
        return switch (benchmarkType) {
            case MEMORY -> new SailRepository(new MemoryStore());
            case NATIVE -> new SailRepository(new NativeStore(NATIVE_PATH, tripleIndexes));
            case LMDB -> new SailRepository(new LmdbStore(LMDB_PATH));
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

            // process arguments
            String storeType = args[0];

            // type of store
            // NATIVE | MEMORY | LMDB
            if (NATIVE.equals(storeType) || MEMORY.equals(storeType) || LMDB.equals(storeType)) {
                System.setProperty("log.storeType", storeType);
            } else {
                throw new IllegalArgumentException("Benchmark store type not supported.");
            }

            // type of transactions TRUE (multi) | FALSE (simple)
            boolean withTransactions = "true".equals(args[1]);
            System.setProperty("log.transactions", withTransactions ? "multi" : "single");

            // size of universe to process
            // int
            int sizeToGenerate = Integer.parseInt(args[2]);
            System.setProperty("log.size", sizeToGenerate + "");

            // complexity of objects
            // TRUE (complex) | FALSE (simple)
            boolean isComplex = "true".equals(args[3]);
            System.setProperty("log.complexity", isComplex ? "complex" : "simple");


            // intermediate requests
            // TRUE | FALSE
            boolean withInterRequests = "true".equals(args[4]);
            System.setProperty("log.interRequests", withInterRequests ? "requests" : "no-requests");

            int interRequestsModulo = -1;

            if (withInterRequests) {
                // how often to do an intermediate request
                // int modulo
                interRequestsModulo = Integer.parseInt(args[5]);
                if (interRequestsModulo > sizeToGenerate) {
                    throw new IllegalArgumentException("Modulo cannot be larger then total size.");
                }
            }
            System.setProperty("log.modulo", interRequestsModulo + "");

            // welcome message
            LogUtils.logWelcome(storeType, withTransactions, sizeToGenerate);

            // get the Repository instance based on store type
            Repository database = getDatabase(storeType);

            // generate list of objects
            List<Object> objects = generateData(sizeToGenerate, isComplex);

            // run either a multi-transaction or single-transaction benchmark
            try (RepositoryConnection connection = database.getConnection()) {

                if (withTransactions) {
                    MultipleTransactions.runBenchmark(connection, objects, interRequestsModulo);
                } else {
                    SingleTransaction.runBenchmark(connection, objects);
                }

                // read statements from database
                readAllFromDatabase(connection, sizeToGenerate);
            } finally {
                // close the connection and shutDown the database
                database.shutDown();
            }

            // benchmark finished, goodbye message
            LogUtils.logGoodbye();

        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            LogUtils.log.error("No or bad arguments provided.");
            LogUtils.log.error(ex.getMessage());
        }
    }
}
