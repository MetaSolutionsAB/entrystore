package org.entrystore;

import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.model.Arguments;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

public class Benchmark {

    private static void tearDown(RepositoryManager repositoryManager) {
        repositoryManager.shutdown();
    }

    private static Config createConfiguration(String storeType) {
        Config config = new PropertiesConfiguration("EntryStore Configuration");
        config.setProperty(Settings.STORE_TYPE, storeType);
        config.setProperty(Settings.BASE_URL, BenchmarkCommons.BASE_URL);
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

            Config configuration = createConfiguration(arguments.getStoreType());
            RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(BenchmarkCommons.BASE_URL, configuration);

            // admin
            repositoryManager.setCheckForAuthorization(false);

            List<Object> persons = generateData(arguments.getSizeToGenerate(), arguments.isComplex());

            try {
                MultipleTransactions.runBenchmark(repositoryManager, persons);

                // reading
                Context context = repositoryManager.getContextManager().getContext(BenchmarkCommons.CONTEXT_ALIAS);
                readAllFromDatabase(context, arguments.getSizeToGenerate());
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
