package org.entrystore;

import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;

import java.net.URI;
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

    private static void readAllFromDatabase(Context context) {

        LogUtils.logType(" READING");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting reading from database at", start);

        for (URI entryURI : context.getEntries()) {
            Entry entry = context.getByEntryURI(entryURI);
            try {
                String dump = entry.getResourceURI() + ": " + entry.getMetadataGraph().objects();
                //System.out.println(dump);
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

            // admin
            repositoryManager.setCheckForAuthorization(false);

            persons = generateData(sizeToGenerate, isComplex);

            try {
                MultipleTransactions.runBenchmark(repositoryManager, persons);

                // reading
                Context context = repositoryManager.getContextManager().getContext(CONTEXT_ALIAS);
                readAllFromDatabase(context);
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
