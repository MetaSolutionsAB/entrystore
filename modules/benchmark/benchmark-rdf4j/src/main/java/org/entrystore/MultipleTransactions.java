package org.entrystore;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MultipleTransactions {

    private static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
        AtomicInteger counter = new AtomicInteger(0);
        return item -> consumer.accept(counter.getAndIncrement(), item);
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

        String namedGraph = BenchmarkOntology.NAMED_GRAPH_PREFIX + "" + (-i);
        IRI context = connection.getValueFactory().createIRI(namedGraph);

        try (RepositoryResult<Statement> result = connection.getStatements(null, null, null, context)) {
            String foo;

            for (Statement statement : result) {
                foo = statement.getObject().stringValue();
                //System.out.printf("Database contains: %s\n", statement);
            }
        }

        LocalDateTime endRead = LocalDateTime.now();
        LogUtils.logTimeDifference("Reading Peter Griffin #" + i + " took", startRead, endRead);
    }

    public static void runBenchmark(RepositoryConnection connection, List<Object> persons, int modulo) {

        LogUtils.logType("POPULATE");
        LogUtils.logType(" INSERT ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model/inserting into database at", start);

        persons.forEach(withCounter((i, person) -> {
            if (person != null) {
                Model model;

                if (modulo == -1 || i % modulo > 0) {
                    List<Object> personInList = new ArrayList<>();
                    personInList.add(person);
                    model = ObjectMapper.populateModelWithPersons(personInList);
                    insertTransaction(connection, model);
                } else {
                    LocalDateTime startInsert = LocalDateTime.now();

                    FakePerson injectedPerson = ObjectGenerator.createSimplePerson(i);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));

                    List<Object> personInList = new ArrayList<>();
                    personInList.add(injectedPerson);

                    model = ObjectMapper.populateModelWithPersons(personInList);
                    insertTransaction(connection, model);

                    LocalDateTime endInsert = LocalDateTime.now();
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
