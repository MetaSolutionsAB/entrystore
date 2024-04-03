package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MultipleTransactions {

    public static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
        AtomicInteger counter = new AtomicInteger(0);
        return item -> consumer.accept(counter.getAndIncrement(), item);
    }

    private static Model populateSinglePerson(FakePerson person) {
        ModelBuilder builder = new ModelBuilder().setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        if (person != null) {
            FakeGenerator.mapToBuilder(person, builder);
        }

        return builder.build();
    }

    private static void insertTransaction(RepositoryConnection connection, FakePerson person) {
        Model model = populateSinglePerson(person);

        try {
            connection.begin();
            connection.add(model);
            connection.commit();
        } catch (Exception ex) {
            LogUtils.log.error(ex.getMessage());
        }
    }

    public static void runBenchmark(RepositoryConnection connection, List<FakePerson> persons, int modulo) {

        LogUtils.logType("POPULATE");
        LogUtils.logType("INSERT");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model/inserting into database at", start);

        persons.forEach(withCounter((i, person) -> {
            if (person != null) {
                if (modulo > 0 && i % modulo > 0) {
                    insertTransaction(connection, person);
                } else {

                    LocalDateTime startInsert = LocalDateTime.now();
                    LogUtils.logDate("Start inserting Peter Griffin #" + i, startInsert);

                    FakePerson injectedPerson = FakeGenerator.createPerson(i);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));


                    insertTransaction(connection, injectedPerson);

                    LocalDateTime endInsert = LocalDateTime.now();
                    LogUtils.logDate("End inserting Peter Griffin #" + i + " at", endInsert);
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);
                }
            }
        }));

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending populating the model/inserting into database at", end);
        LogUtils.logTimeDifference("Populating the model/inserting into database took", start, end);
    }
}
