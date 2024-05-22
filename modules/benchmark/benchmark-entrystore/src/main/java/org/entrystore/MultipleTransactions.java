package org.entrystore;

import org.entrystore.generator.ObjectGenerator;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.model.FakePerson;
import org.entrystore.repository.RepositoryManager;

import java.time.LocalDateTime;
import java.util.List;

public class MultipleTransactions {

    public static void runBenchmark(RepositoryManager repositoryManager, List<Object> persons, int modulo, boolean withMultiContext) {

        LogUtils.logType("   ADD  ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting adding to context at", start);

        ContextManager contextManager = repositoryManager.getContextManager();
        Entry newContext = contextManager.createResource(null, GraphType.Context, null, null);
        contextManager.setName(newContext.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_0");

        persons.forEach(BenchmarkCommons.withCounter((i, person) -> {
            if (person != null) {
                Context moduloContext = contextManager.getContext(BenchmarkCommons.CONTEXT_ALIAS + "_" + (withMultiContext && modulo > 0 ? i / modulo : 0));

                if (modulo < 0 || i % modulo > 0) {
                    ObjectMapper.mapObjectToContext(moduloContext, person);
                } else {
                    LocalDateTime startInsert = LocalDateTime.now();

                    FakePerson injectedPerson = ObjectGenerator.createSimplePerson(i);
                    injectedPerson.setIdentifier(-i);
                    injectedPerson.setFirstName("Peter" + (i / modulo));
                    injectedPerson.setLastName("Griffin" + (i / modulo));

                    if (withMultiContext) {
                        Entry newModuloContext = contextManager.createResource(null, GraphType.Context, null, null);
                        contextManager.setName(newModuloContext.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_" + (i + 1) / modulo);
                        ObjectMapper.mapObjectToContext((Context) newModuloContext.getResource(), person);
                    } else {
                        ObjectMapper.mapObjectToContext(moduloContext, injectedPerson);
                    }

                    LocalDateTime endInsert = LocalDateTime.now();
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);
                }
            }
        }));

        LocalDateTime end = LocalDateTime.now();
        LogUtils.logDate("Ending adding to context at", end);
        LogUtils.logTimeDifference("Adding to context took", start, end);
    }
}
