package org.entrystore;

import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.model.FakeGenerator;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;

import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

    private static final String NATIVE = "native";
    private static final String MEMORY = "memory";
    private static final String LMDB = "lmdb";
    private static final String BASE_URL = "http://localhost:8181/";
    public static final String CONTEXT_ALIAS = "benchmark";

    private static void tearDown(RepositoryManager repositoryManager) {
        repositoryManager.shutdown();
    }

    private static Config createConfiguration(String storeType) {
        Config config = new PropertiesConfiguration("EntryStore Configuration");
        config.setProperty(Settings.STORE_TYPE, storeType);
        config.setProperty(Settings.BASE_URL, BASE_URL);
        config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
        config.setProperty(Settings.SOLR, "off");

        return config;
    }

    private static List<Object> generateSimpleData(int sizeToGenerate) {

        LogUtils.logType("GENERATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting generating data at", start);

        List<Object> persons = FakeGenerator.createSimplePersonList(sizeToGenerate);

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended generating data at", end);
        LogUtils.logTimeDifference("Generating data took", start, end);

        return persons;
    }

    private static List<Object> generateComplexData(int sizeToGenerate) {

        LogUtils.logType("GENERATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting generating data at", start);

        List<Object> persons = FakeGenerator.createComplexPersonList(sizeToGenerate);

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended generating data at", end);
        LogUtils.logTimeDifference("Generating data took", start, end);

        return persons;
    }

    public static void main(String[] args) {

        try {

            // process arguments
            String storeType = args[0];

            if (NATIVE.equals(storeType) || MEMORY.equals(storeType) || LMDB.equals(storeType)) {
                System.setProperty("log.storeType", storeType);
            } else {
                throw new IllegalArgumentException("Benchmark type not supported.");
            }

            boolean withTransactions = "true".equals(args[1]);
            System.setProperty("log.transactions", withTransactions ? "multi" : "single");

            int sizeToGenerate = Integer.parseInt(args[2]);
            System.setProperty("log.size", sizeToGenerate + "");

            boolean isComplex = "true".equals(args[3]);
            System.setProperty("log.complexity", isComplex ? "complex" : "simple");

            boolean withInterRequests = "true".equals(args[4]);
            System.setProperty("log.interRequests", withInterRequests ? "requests" : "no-requests");

            int interRequestsModulo = -1;

            if (withInterRequests) {
                interRequestsModulo = Integer.parseInt(args[5]);
                if (interRequestsModulo > sizeToGenerate) {
                    throw new IllegalArgumentException("Modulo cannot be larger then total size.");
                }
            }
            System.setProperty("log.modulo", interRequestsModulo + "");

            // welcome message
            LogUtils.logWelcome(storeType, withTransactions, sizeToGenerate);

            List<Object> persons;

            Config configuration = createConfiguration(storeType);
            RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(BASE_URL, configuration);
            repositoryManager.setCheckForAuthorization(false);

            if (isComplex) {
                // generate list of Persons with Addresses, Spouses and Companies. Spouse has also a Company and Address. Company has also an Address.
                persons = generateComplexData(sizeToGenerate);
            } else {
                // generate list of Persons with Addresses, 1 Person has exactly 1 Address
                persons = generateSimpleData(sizeToGenerate);
            }

            try {
                MultipleTransactions.runBenchmark(repositoryManager, persons);

                // reading
                Context context = repositoryManager.getContextManager().getContext(CONTEXT_ALIAS);

                for (int i = 1; i <= (persons.size() * 8); i++) {
                    Entry entry = context.get(i + "");
                    try {
                        System.out.println(entry.getResourceURI() + ": " + entry.getMetadataGraph().objects());
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }


            } finally {
                // close the connection and shutDown the database
                tearDown(repositoryManager);
            }

            // benchmark finished, goodbye message
            LogUtils.logGoodbye();

        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            LogUtils.log.error("No or bad arguments provided.");
            LogUtils.log.error(ex.getMessage());
        }
    }
}
