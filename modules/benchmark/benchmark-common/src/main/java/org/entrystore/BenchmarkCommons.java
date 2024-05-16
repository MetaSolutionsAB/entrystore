package org.entrystore;

import org.entrystore.model.Arguments;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class BenchmarkCommons {

    public static final File NATIVE_PATH = new File("./testdata/native_store");
    public static final File ENTRY_STORE_NATIVE_PATH = new File("testdata/native_store");
    public static final File LMDB_PATH = new File("./testdata/lmdb_store");
    public static final File ENTRY_STORE_LMDB_PATH = new File("testdata/lmdb_store");
    public static final String NATIVE = "native";
    public static final String MEMORY = "memory";
    public static final String LMDB = "lmdb";
    public static final String INDEXES = "cspo,spoc";
    public static final String BASE_URL = "http://localhost:8181/";
    public static final String CONTEXT_ALIAS = "benchmark";

    public static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
        AtomicInteger counter = new AtomicInteger(0);
        return item -> consumer.accept(counter.getAndIncrement(), item);
    }

    public static Arguments processArguments(String[] args) {

        Arguments arguments = new Arguments();

        // process arguments
        String storeType = args[0];

        // type of store
        // NATIVE | MEMORY | LMDB
        if (NATIVE.equals(storeType) || MEMORY.equals(storeType) || LMDB.equals(storeType)) {
            arguments.setStoreType(storeType);
            System.setProperty("log.storeType", storeType);
        } else {
            throw new IllegalArgumentException("Benchmark store type not supported.");
        }

        // type of transactions TRUE (multi) | FALSE (simple)
        arguments.setWithTransactions("true".equals(args[1]));
        System.setProperty("log.transactions", arguments.isWithTransactions() ? "multi" : "single");

        // size of universe to process
        // int
        arguments.setSizeToGenerate(Integer.parseInt(args[2]));
        System.setProperty("log.size", arguments.getSizeToGenerate() + "");

        // complexity of objects
        // TRUE (complex) | FALSE (simple)
        arguments.setComplex("true".equals(args[3]));
        System.setProperty("log.complexity", arguments.isComplex() ? "complex" : "simple");

        // intermediate requests
        // TRUE | FALSE
        arguments.setWithInterRequests("true".equals(args[4]));
        System.setProperty("log.interRequests", arguments.isWithInterRequests() ? "requests" : "no-requests");

        if (arguments.isWithInterRequests()) {
            // how often to do an intermediate request
            // int modulo
            arguments.setInterRequestsModulo(Integer.parseInt(args[5]));
            if (arguments.getInterRequestsModulo() > arguments.getSizeToGenerate()) {
                throw new IllegalArgumentException("Modulo cannot be larger then total size.");
            }
        }
        System.setProperty("log.modulo", arguments.getInterRequestsModulo() + "");

        // welcome message
        LogUtils.logWelcome(storeType, arguments.isWithTransactions(), arguments.getSizeToGenerate());

        return arguments;
    }
}
