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

    private static final LogUtils logUtils = new LogUtils();
    private static final String START = "start";
    private static final String END = "end";

    private static Model populateModel(List<FakePerson> persons) {
        ModelBuilder builder = new ModelBuilder()
                .setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        persons.forEach(person -> builder
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
                .add(BenchmarkOntology.HAS_ZIP_CODE, person.getAddress().getZipCode()));

        return builder.build();
    }

    private static void runBenchmark(String benchmarkType, Model model) {

        LocalDateTime start = LocalDateTime.now();
        logUtils.logDate(benchmarkType, START, start);

        Repository database;
        if (NATIVE.equals(benchmarkType)){
            database = new SailRepository(new NativeStore(NATIVE_PATH));
        } else {
            database = new SailRepository(new MemoryStore());
        }

        try (RepositoryConnection connection = database.getConnection()) {

            connection.begin();
            connection.add(model);
            connection.commit();

            try (RepositoryResult<Statement> result = connection.getStatements(null, null, null)) {
                //
                //for (Statement statement : result) {
                //    System.out.printf("Database contains: %s\n", statement);
                //}
            }
        } finally {
            database.shutDown();
        }
        LocalDateTime end = LocalDateTime.now();
        logUtils.logDate(benchmarkType, END, end);
        logUtils.logTimeDifference(benchmarkType, start, end);

    }

    public static void main(String[] args) {

        final String benchmarkType = args[0];
        final int sizeToGenerate = Integer.parseInt(args[1]);

        FakeGenerator generator = new FakeGenerator();
        List<FakePerson> persons = generator.createPersonList(sizeToGenerate);

        Model model = populateModel(persons);

        //Rio.write(model, System.out, RDFFormat.TURTLE);

        switch (benchmarkType){
            case NATIVE -> runBenchmark(NATIVE, model);
            default -> runBenchmark(MEMORY, model);
        }
    }
}
