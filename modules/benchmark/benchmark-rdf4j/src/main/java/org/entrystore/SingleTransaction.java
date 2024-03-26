package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.time.LocalDateTime;
import java.util.List;

public class SingleTransaction {

    private static Model populateModel(List<FakePerson> persons) {

        LogUtils.logType("POPULATE");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model at", start);

        ModelBuilder builder = new ModelBuilder()
                .setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        persons.forEach(person -> {
            if (person != null) {
                FakeGenerator.mapToBuilder(person, builder);
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

    public static void runBenchmark(RepositoryConnection connection, List<FakePerson> persons) {

        Model model = populateModel(persons);
        insertToDatabase(connection, model);
    }
}
