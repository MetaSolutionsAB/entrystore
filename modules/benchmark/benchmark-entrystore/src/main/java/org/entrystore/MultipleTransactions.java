package org.entrystore;

import org.entrystore.generator.ObjectGenerator;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.model.FakePerson;
import org.entrystore.repository.RepositoryManager;

import java.time.LocalDateTime;
import java.util.List;

public class MultipleTransactions {

    private static void addToContext(Context context, List<Object> persons, int modulo) {

        LogUtils.logType("   ADD  ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting adding to context at", start);

        persons.forEach(BenchmarkCommons.withCounter((i, person) -> {
            if (person != null) {
                if (modulo == -1 || i % modulo > 0) {
                    ObjectMapper.mapObjectToContext(context, person);
                } else {
                    LocalDateTime startInsert = LocalDateTime.now();

                    FakePerson injectedPerson = ObjectGenerator.createSimplePerson(i);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));

                    ObjectMapper.mapObjectToContext(context, injectedPerson);

                    LocalDateTime endInsert = LocalDateTime.now();
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);
                }

            }
        }));

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending adding to context at", end);
        LogUtils.logTimeDifference("Adding to context took", start, end);
    }

    public static void runBenchmark(RepositoryManager repositoryManager, List<Object> persons, int modulo) {

        // A new single Context
        Entry contextEntry = repositoryManager.getContextManager().createResource(null, GraphType.Context, null, null);
        Context context = (Context) contextEntry.getResource();
        repositoryManager.getContextManager().setName(contextEntry.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS);
        addToContext(context, persons, modulo);
    }
}
