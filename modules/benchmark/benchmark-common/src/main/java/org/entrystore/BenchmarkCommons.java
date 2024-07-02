package org.entrystore;

import org.entrystore.model.Arguments;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class BenchmarkCommons {

    public static final String NATIVE = "native";
    public static final String MEMORY = "memory";
    public static final String LMDB = "lmdb";
    public static final String INDEXES = "cspo,spoc";
    public static final String BASE_URL = "http://localhost:8181/";
    public static final String CONTEXT_ALIAS = "benchmark";
    public static final String BENCHMARK_USER = "Benchmark User";
    public static final String BENCHMARK_USER_SECRET = "thisissecret";

    public static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
        AtomicInteger counter = new AtomicInteger(0);
        return item -> consumer.accept(counter.getAndIncrement(), item);
    }

    public static Arguments processArguments(String[] args) throws IOException {

        Arguments arguments = new Arguments();

        // process arguments
        String storeType = args[0];

        // type of store
        // NATIVE | MEMORY | LMDB
        if (NATIVE.equals(storeType) || MEMORY.equals(storeType) || LMDB.equals(storeType)) {
            arguments.setStoreType(storeType);
            arguments.setStorePath();
            System.setProperty("log.storeType", storeType);
        } else {
            throw new IllegalArgumentException("Benchmark store type not supported.");
        }

        // type of transactions m (multi) | s (simple)
        arguments.setWithTransactions("m".equals(args[1]));
        System.setProperty("log.transactions", arguments.isWithTransactions() ? "multi" : "single");

        // size of universe to process
        // int
        arguments.setSizeToGenerate(Integer.parseInt(args[2]));
        System.setProperty("log.size", arguments.getSizeToGenerate() + "");

        // complexity of objects
        // c (complex) | s (simple)
        arguments.setComplex("c".equals(args[3]));
        System.setProperty("log.complexity", arguments.isComplex() ? "complex" : "simple");

        // intermediate requests
        // if number is higher then 0
        arguments.setInterRequestsModulo(Integer.parseInt(args[4]));
        arguments.setWithInterRequests(Integer.parseInt(args[4]) > 0);
        System.setProperty("log.interRequests", arguments.isWithInterRequests() ? "requests" : "no-requests");

        if (arguments.isWithInterRequests()) {
            // how often to do an intermediate request
            // int modulo
            if (arguments.getInterRequestsModulo() > arguments.getSizeToGenerate()) {
                throw new IllegalArgumentException("Modulo cannot be larger then total size.");
            }

            // multiple contexts
            arguments.setWithInterContexts("true".equals(args[5]));
        }
        System.setProperty("log.modulo", arguments.getInterRequestsModulo() + "");

        // acl activated
        // on | off
        arguments.setWithAcl("on".equals(args[6]));
        System.setProperty("log.acl", arguments.isWithAcl() ? "on" : "off");

        // set Solr directory path
        arguments.setSolrPath();

        // welcome message
        LogUtils.logWelcome(storeType, arguments.isWithTransactions(), arguments.getSizeToGenerate());

        return arguments;
    }
}
