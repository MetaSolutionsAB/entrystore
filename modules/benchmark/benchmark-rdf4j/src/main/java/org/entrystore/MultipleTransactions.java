package org.entrystore;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.model.FakeGenerator;
import org.entrystore.model.FakePerson;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.time.LocalDateTime;
import java.util.List;

public class MultipleTransactions {

    private static Model populateSinglePerson(FakePerson person) {
        ModelBuilder builder = new ModelBuilder().setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        if (person != null) {
            FakeGenerator.mapToBuilder(person, builder);
        }

        return builder.build();
    }

    public static void runBenchmark(RepositoryConnection connection, List<FakePerson> persons) {

        LogUtils.logType("POPULATE");
        LogUtils.logType("INSERT");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting populating the model/inserting into database at", start);

        persons.forEach(person -> {
            if (person != null) {
                Model model = populateSinglePerson(person);

                connection.begin();
                connection.add(model);
                connection.commit();

            }
        });

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending populating the model/inserting into database at", end);
        LogUtils.logTimeDifference("Populating the model/inserting into database took", start, end);
    }
}
