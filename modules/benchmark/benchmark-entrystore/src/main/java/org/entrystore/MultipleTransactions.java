package org.entrystore;

import org.entrystore.mapper.ObjectMapper;
import org.entrystore.repository.RepositoryManager;

import java.time.LocalDateTime;
import java.util.List;

public class MultipleTransactions {

    private static void addToContext(Context context, List<Object> persons) {

        LogUtils.logType("   ADD  ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting adding to context at", start);

        persons.forEach(person -> {
            if (person != null) {
                ObjectMapper.mapObjectToContext(context, person);
            }
        });

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending adding to context at", end);
        LogUtils.logTimeDifference("Adding to context took", start, end);
    }

    public static void runBenchmark(RepositoryManager repositoryManager, List<Object> persons) {

        // A new single Context
        Entry contextEntry = repositoryManager.getContextManager().createResource(null, GraphType.Context, null, null);
        Context context = (Context) contextEntry.getResource();
        repositoryManager.getContextManager().setName(contextEntry.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS);
        addToContext(context, persons);
    }
}
