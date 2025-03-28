package org.entrystore;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.entrystore.model.Arguments;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class BenchmarkCommons {

	public static final String NATIVE = "native";
	public static final String MEMORY = "memory";
	public static final String LMDB = "lmdb";
	public static final String SIMPLE = "simple";
	public static final String COMPLEX = "complex";
	public static final String INDEXES = "cspo,spoc";
	public static final String BASE_URL = "http://localhost:8181/";
	public static final String CONTEXT_ALIAS = "benchmark";
	public static final String BENCHMARK_USER = "Benchmark User";
	public static final String BENCHMARK_USER_SECRET = "thisissecret";

	public static <T> Consumer<T> withCounter(BiConsumer<Integer, T> consumer) {
		AtomicInteger counter = new AtomicInteger(0);
		return item -> consumer.accept(counter.getAndIncrement(), item);
	}

	private static Option createOption(String shortName, String longName, String argName, String description, boolean required) {
		return Option.builder(shortName)
				.longOpt(longName)
				.argName(argName)
				.desc(description)
				.hasArg()
				.required(required)
				.build();
	}

	private static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setLeftPadding(2);
		formatter.printHelp("benchmark", options, true);
	}

	public static Arguments processArguments(String[] args) throws IOException {

		Arguments arguments = new Arguments();

		Option storTypeOption = createOption("s", "store", "STORE", "Type of store: 'native' | 'memory' | 'lmdb'.", true);
		Option sizeOfUniverseOption = createOption("u", "universe", "UNIVERSE", "Size of universe/objects to process: @int.", true);
		Option complexityOption = createOption("c", "complexity", "COMPLEXITY", "Complexity of universe/objects to process: 'complex' | 'simple'.", false);
		Option moduloOption = createOption("m", "modulo", "MODULO", "Record intermediate requests with modulo: @int.", false);
		Option isWithTransactionsOption = createOption("t", "transaction", "TRANSACTION", "Run with multiple transactions: @boolean.", false);
		Option isWithInterContextsOption = createOption("i", "intercontexts", "INTERCONTEXTS", "Run with interim contexts: @boolean.", false);
		Option isWithAclOption = createOption("a", "acl", "ACL", "Run with ACL: @boolean.", false);
		Option storePathOption = createOption("p", "path", "PATH", "Store path: @string.", false);
		Option baseUrlOption = createOption("b", "base", "BASE", "Base URL: @string.", false);

		Options options = new Options();
		options.addOption(storTypeOption);
		options.addOption(sizeOfUniverseOption);
		options.addOption(complexityOption);
		options.addOption(moduloOption);
		options.addOption(isWithTransactionsOption);
		options.addOption(isWithInterContextsOption);
		options.addOption(isWithAclOption);
		options.addOption(storePathOption);
		options.addOption(baseUrlOption);

		try {
			CommandLineParser commandLineParser = new DefaultParser();
			CommandLine commandLine = commandLineParser.parse(options, args);

			String storeType = commandLine.hasOption("s") ? commandLine.getOptionValue(storTypeOption) : null;
			if (NATIVE.equals(storeType) || MEMORY.equals(storeType) || LMDB.equals(storeType)) {
				arguments.setBaseUrl(commandLine.getOptionValue(baseUrlOption) != null ? commandLine.getOptionValue(baseUrlOption) : BASE_URL);
				if (commandLine.getOptionValue(storePathOption) != null) {
					arguments.setStorePath(commandLine.getOptionValue(storePathOption));
				}else arguments.setStorePath();

				arguments.setStoreType(storeType);
				arguments.setSolrPath();
				System.setProperty("log.storeType", storeType);
			} else {
				System.err.println("Benchmark store type not supported.");
				printHelp(options);
				System.exit(1);
			}

			try {
				arguments.setSizeToGenerate(Integer.parseInt(commandLine.getOptionValue(sizeOfUniverseOption)));
				if (arguments.getSizeToGenerate() > 0) {
					System.setProperty("log.size", arguments.getSizeToGenerate() + "");
				} else {
					throw new NumberFormatException();
				}
			} catch (NullPointerException | NumberFormatException ex) {
				System.err.println("Size of the universe must be an @int larger then 0.");
				printHelp(options);
				System.exit(1);
			}

			String complexityType = commandLine.hasOption("c") ? commandLine.getOptionValue(complexityOption) : null;
			if (complexityType == null) {
				arguments.setComplex(false);
				System.setProperty("log.complexity", SIMPLE);
			} else if (SIMPLE.equals(complexityType) || COMPLEX.equals(complexityType)) {
				arguments.setComplex(COMPLEX.equals(complexityType));
				System.setProperty("log.complexity", complexityType);
			} else {
				System.err.println("Complexity type not supported.");
				printHelp(options);
				System.exit(1);
			}

			String interRequestModulo = commandLine.hasOption("m") ? commandLine.getOptionValue(moduloOption) : null;
			if (interRequestModulo != null) {
				try {
					arguments.setInterRequestsModulo(Integer.parseInt(commandLine.getOptionValue(moduloOption)));

					if (arguments.getInterRequestsModulo() > 0) {
						System.setProperty("log.modulo", arguments.getInterRequestsModulo() + "");
						arguments.setWithInterRequests(true);
					} else {
						throw new NumberFormatException();
					}

					System.setProperty("log.interRequests", arguments.isWithInterRequests() ? "requests" : "no-requests");

					if (arguments.isWithInterRequests()) {
						if (arguments.getInterRequestsModulo() > arguments.getSizeToGenerate()) {
							throw new IllegalArgumentException("Modulo cannot be larger then total size.");
						} else {
							boolean isWithInterContexts = commandLine.hasOption("i") && "true".equals(commandLine.getOptionValue(isWithInterContextsOption));
							arguments.setWithInterContexts(isWithInterContexts);
						}
					}
				} catch (NullPointerException | NumberFormatException ex) {
					System.err.println("Size of the modulo must be an @int larger then 0.");
					printHelp(options);
					System.exit(1);
				} catch (IllegalArgumentException ex) {
					System.err.println(ex.getMessage());
					printHelp(options);
					System.exit(1);
				}
			}

			boolean isWithTransaction = !commandLine.hasOption("t") || "false".equals(commandLine.getOptionValue(isWithTransactionsOption));
			arguments.setWithTransactions(isWithTransaction);
			System.setProperty("log.transactions", arguments.isWithTransactions() ? "multi" : "single");

			boolean isWithAcl = !commandLine.hasOption("a") || "false".equals(commandLine.getOptionValue(isWithTransactionsOption));
			arguments.setWithAcl(isWithAcl);
			System.setProperty("log.acl", arguments.isWithAcl() ? "on" : "off");

			// welcome message
			LogUtils.logWelcome(storeType, arguments.isWithTransactions(), arguments.getSizeToGenerate());

		} catch (ParseException e) {
			System.err.println(e.getMessage() + "\n");
			printHelp(options);
			System.exit(1);
		}

		return arguments;
	}
}
