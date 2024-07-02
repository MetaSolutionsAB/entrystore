package org.entrystore;

import org.entrystore.generator.ObjectGenerator;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.model.FakePerson;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.util.SolrSearchIndex;

import java.time.LocalDateTime;
import java.util.List;

public class MultipleTransactions {

    public static void runBenchmark(
            RepositoryManager repositoryManager,
            List<Object> persons,
            int modulo,
            boolean withMultiContext,
            boolean isWithAcl) throws InterruptedException {

        LogUtils.logType("   ADD  ");

        LocalDateTime start = LocalDateTime.now();
        LogUtils.logDate("Starting adding to context and sending data to Solr at", start);

        ContextManager contextManager = repositoryManager.getContextManager();
        PrincipalManager principalManager = repositoryManager.getPrincipalManager();

        Entry newContext = contextManager.createResource(null, GraphType.Context, null, null);
        contextManager.setName(newContext.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_0");

        User benchmarkUser;

        if (isWithAcl) {
            benchmarkUser = createBenchmarkUser(principalManager);

            try {
                newContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, benchmarkUser.getURI());
                benchmarkUser.setHomeContext((Context) newContext.getResource());
            } finally {
                principalManager.setAuthenticatedUserURI(benchmarkUser.getURI());
            }
        } else {
            benchmarkUser = null;
        }

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

                        if (isWithAcl) {
                            principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());
                        }

                        Entry newModuloContext = contextManager.createResource(null, GraphType.Context, null, null);
                        contextManager.setName(newModuloContext.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_" + (i + 1) / modulo);

                        if (isWithAcl) {
                            newModuloContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, benchmarkUser.getURI());
                            principalManager.setAuthenticatedUserURI(benchmarkUser.getURI());
                        }

                        ObjectMapper.mapObjectToContext((Context) newModuloContext.getResource(), person);

                    } else {
                        ObjectMapper.mapObjectToContext(moduloContext, injectedPerson);
                    }

                    LocalDateTime endInsert = LocalDateTime.now();
                    LogUtils.logTimeDifference("Inserting Peter Griffin #" + i + " took", startInsert, endInsert);
                }
            }
        }));

        LocalDateTime endContext = LocalDateTime.now();
        LogUtils.logDate("Ending adding to context at", endContext);
        LogUtils.logTimeDifference("Adding to context took", start, endContext);

        SolrSearchIndex solrSearchIndex = (SolrSearchIndex) repositoryManager.getIndex();

        while (solrSearchIndex.getPostQueueSize() > 0) {
            Thread.sleep(500);
        }

        // To solve the race condition when queue is empty for low amount of instances.
        Thread.sleep(500);

        LocalDateTime endSolr = LocalDateTime.now();
        LogUtils.logDate("Ending sending data to Solr at", endSolr);
        LogUtils.logTimeDifference("Adding to context and sending data to Solr took", start, endSolr);

    }

    private static User createBenchmarkUser(PrincipalManager principalManager) {
        principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

        // benchmark user
        Entry benchmarkUserEntry = principalManager.createResource(null, GraphType.User, null, null);
        User benchmarkUser = (User) benchmarkUserEntry.getResource();
        principalManager.setPrincipalName(benchmarkUserEntry.getResourceURI(), BenchmarkCommons.BENCHMARK_USER);
        benchmarkUser.setSecret(BenchmarkCommons.BENCHMARK_USER_SECRET);

        return benchmarkUser;
    }
}
