package org.entrystore;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.model.FakeComplexPerson;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MultipleTransactions {

    private static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
        AtomicInteger counter = new AtomicInteger(0);
        return item -> consumer.accept(counter.getAndIncrement(), item);
    }

    private static Model populateSingleSimplePerson(FakePerson person) {
        ModelBuilder builder = new ModelBuilder().setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        if (person != null) {
            FakeGenerator.mapSimplePersonToBuilder(person, builder);
        }

        return builder.build();
    }

    private static Model populateSingleComplexPerson(FakeComplexPerson person) {
        ModelBuilder builder = new ModelBuilder().setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        if (person != null) {
            FakeGenerator.mapComplexPersonToBuilder(person, builder);
        }

        return builder.build();
    }

    private static void insertTransaction(RepositoryConnection connection, Model model) {
        try {
            connection.begin();
            connection.add(model);
            connection.commit();
        } catch (Exception ex) {
            LogUtils.log.error(ex.getMessage());
        }
    }

    public static void readSpecificPerson(RepositoryConnection connection, int i) {

        LocalDateTime startRead = LocalDateTime.now();
        //LogUtils.logDate("Start reading Peter Griffin #" + i, startRead);

        String namedGraph = BenchmarkOntology.NAMED_GRAPH_PREFIX + "" + (-i);
        IRI context = connection.getValueFactory().createIRI(namedGraph);

        try (RepositoryResult<Statement> result = connection.getStatements(null, null, null, context)) {

            for (Statement statement : result) {
                //System.out.printf("Database contains: %s\n", statement);
            }
        }

        LocalDateTime endRead = LocalDateTime.now();
        //LogUtils.logDate("End reading Peter Griffin #" + i + " at", endRead);
        LogUtils.logTimeDifference("Reading Peter Griffin #" + i + " took", startRead, endRead);
    }

    public static void runSimpleObjectsBenchmark(RepositoryConnection connection, List<FakePerson> persons, int modulo) {

        LogUtils.logType("POPULATE");
        LogUtils.logType(" INSERT ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model/inserting into database at", start);

        persons.forEach(withCounter((i, person) -> {
            if (person != null) {
                Model model;

                if (modulo == -1 || i % modulo > 0) {
                    model = populateSingleSimplePerson(person);
                    insertTransaction(connection, model);
                } else {
                    LocalDateTime startInsert = LocalDateTime.now();
                    //LogUtils.logDate("Start inserting Peter Griffin #" + i, startInsert);

                    FakePerson injectedPerson = FakeGenerator.createSimplePerson(i);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));

                    model = populateSingleSimplePerson(injectedPerson);
                    insertTransaction(connection, model);
                    readSpecificPerson(connection, i);
                    readSpecificPerson(connection, modulo);

                    LocalDateTime endInsert = LocalDateTime.now();
                    //LogUtils.logDate("End inserting Peter Griffin #" + i + " at", endInsert);
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);
                }
            }
        }));

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending populating the model/inserting into database at", end);
        LogUtils.logTimeDifference("Populating the model/inserting into database took", start, end);
    }

    public static void runComplexObjectsBenchmark(RepositoryConnection connection, List<FakeComplexPerson> persons, int modulo) {

        LogUtils.logType("POPULATE");
        LogUtils.logType(" INSERT ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model/inserting into database at", start);

        persons.forEach(withCounter((i, person) -> {
            if (person != null) {
                Model model;

                if (modulo == -1 || i % modulo > 0) {
                    model = populateSingleComplexPerson(person);
                    insertTransaction(connection, model);
                } else {

                    LocalDateTime startInsert = LocalDateTime.now();
                    //LogUtils.logDate("Start inserting Peter Griffin #" + i, startInsert);

                    FakeComplexPerson injectedSpouse = FakeGenerator.createComplexPerson(0, null);
                    FakeComplexPerson injectedPerson = FakeGenerator.createComplexPerson(i, injectedSpouse);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));

                    model = populateSingleComplexPerson(injectedPerson);
                    insertTransaction(connection, model);

                    LocalDateTime endInsert = LocalDateTime.now();
                    //LogUtils.logDate("End inserting Peter Griffin #" + i + " at", endInsert);
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);

                    readSpecificPerson(connection, i);
                    readSpecificPerson(connection, modulo);
                }
            }
        }));

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending populating the model/inserting into database at", end);
        LogUtils.logTimeDifference("Populating the model/inserting into database took", start, end);
    }
}
