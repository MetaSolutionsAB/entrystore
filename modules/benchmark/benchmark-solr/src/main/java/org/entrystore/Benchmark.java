package org.entrystore;

import org.apache.commons.io.FileUtils;
import org.entrystore.config.Config;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.model.Arguments;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.SolrSearchIndex;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class Benchmark {

    private static Config createConfiguration(String storeType, String tempPath) throws IOException {
        Config config = new PropertiesConfiguration("EntryStore Configuration");
        config.setProperty(Settings.STORE_TYPE, storeType);
        if (storeType.equalsIgnoreCase("native")) {
            String storePath = "file:///" + BenchmarkCommons.ENTRY_STORE_NATIVE_PATH.getAbsolutePath().replace('\\', '/');
            config.addProperty(Settings.STORE_PATH, storePath);
            config.addProperty(Settings.STORE_INDEXES, BenchmarkCommons.INDEXES);
        } else if (storeType.equalsIgnoreCase("lmdb")) {
            String storePath = "file:///" + BenchmarkCommons.ENTRY_STORE_LMDB_PATH.getAbsolutePath().replace('\\', '/');
            config.addProperty(Settings.STORE_PATH, storePath);
        }
        config.setProperty(Settings.BASE_URL, BenchmarkCommons.BASE_URL);
        config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
        config.setProperty(Settings.SOLR, "on");
        config.setProperty(Settings.SOLR_URL, tempPath);

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

            Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), "benchmark-" + UUID.randomUUID());
            String tempPath = Files.createDirectories(path).toFile().getAbsolutePath();

            Config configuration = createConfiguration(arguments.getStoreType(), tempPath);
            RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(BenchmarkCommons.BASE_URL, configuration);

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

                while (((SolrSearchIndex) repositoryManager.getIndex()).getPostQueueSize() > 0) {

                }

                // To solve the race condition when queue is empty for low amount of instances.
                Thread.sleep(500);

            } finally {
                // close the connection and shutDown the database and solr
                repositoryManager.shutdown();
                FileUtils.deleteDirectory(new File(tempPath));
            }

            // benchmark finished, goodbye message
            LogUtils.logGoodbye();

        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | InterruptedException ex) {
            LogUtils.log.error("No or bad arguments provided.");
            LogUtils.log.error(ex.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}