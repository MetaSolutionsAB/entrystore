package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;


public class Benchmark {

    private static final File NATIVE_PATH = new File("modules/benchmark/benchmark-rdf4j/src/main/resources/native_store");
    private static final String NATIVE = "Native";
    private static final String MEMORY = "Memory";

    private static String benchmarkType = MEMORY;
    private static int sizeToGenerate = 1;
    private static Repository database;

    private static List<FakePerson> generateData(int sizeToGenerate) {

        LogUtils.logType("GENERATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting generating data at", start);

        FakeGenerator generator = new FakeGenerator();

        //List<FakePerson> persons = generator.createPersonList(sizeToGenerate);
        List<FakePerson> persons = generator.createPersonListParallel(sizeToGenerate);

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended generating data at", end);
        LogUtils.logTimeDifference("Generating data took", start, end);

        return persons;
    }

    private static Model populateModel(List<FakePerson> persons) {

        LogUtils.logType("POPULATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model at", start);

        ModelBuilder builder = new ModelBuilder()
                .setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        persons.forEach(person -> {
            if (person != null) {
                builder
                        .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getIdentifier()))
                        .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getIdentifier())
                        .add(RDF.TYPE, BenchmarkOntology.ARTIST)
                        .add(FOAF.FIRST_NAME, person.getFirstName())
                        .add(FOAF.LAST_NAME, person.getLastName())
                        .add(BenchmarkOntology.HAS_ITERATOR, person.getIterator())
                        .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + person.getAddress().getIdentifier())

                        .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getAddress().getIdentifier()))
                        .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getAddress().getIdentifier())
                        .add(BenchmarkOntology.HAS_ITERATOR, person.getAddress().getIterator())
                        .add(BenchmarkOntology.HAS_STREET, person.getAddress().getStreet())
                        .add(BenchmarkOntology.HAS_CITY, person.getAddress().getCity())
                        .add(BenchmarkOntology.HAS_ZIP_CODE, person.getAddress().getZipCode());
            }
        });

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending populating the model at", end);
        LogUtils.logTimeDifference("Populating the model took", start, end);

        return builder.build();
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

    private static void readFromDatabase(RepositoryConnection connection) {

        LogUtils.logType(" READING");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting reading from database at", start);

        try (RepositoryResult<Statement> result = connection.getStatements(null, null, null)) {

            for (Statement statement : result) {
                //System.out.printf("Database contains: %s\n", statement);
            }
        }

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ended reading from database at", end);
        LogUtils.logTimeDifference("Reading from database took", start, end);
    }

    private static void runBenchmark(Model model) {

        try (RepositoryConnection connection = database.getConnection()) {

            insertToDatabase(connection, model);
            readFromDatabase(connection);

        } finally {
            database.shutDown();
        }
    }

    public static void main(String[] args) {

        try {
            benchmarkType = args[0];
            sizeToGenerate = Integer.parseInt(args[1]);
        } catch (IllegalArgumentException ex) {
            LogUtils.log.warn("No arguments provided.");
        }

        LogUtils.logWelcome(benchmarkType, sizeToGenerate);

        List<FakePerson> persons = generateData(sizeToGenerate);

        Model model = populateModel(persons);

        if (NATIVE.equals(benchmarkType)) {
            database = new SailRepository(new NativeStore(NATIVE_PATH));
        } else {
            database = new SailRepository(new MemoryStore());
        }

        //Rio.write(model, System.out, RDFFormat.TURTLE);
        runBenchmark(model);

        LogUtils.logGoodbye();
    }
}
