package org.entrystore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogUtils {

	private static long getTotalCommittedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted();
	}

	private static long getTotalUsedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
	}

	public static final Logger log = LoggerFactory.getLogger(LogUtils.class);

	private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private static void logStars(boolean withSpace) {
		if (withSpace) {
			log.info("*****                                                                     *****");
		} else {
			log.info("*******************************************************************************");
		}
	}

	protected static void logJvmSettings() {
		// JVM
		log.info("totalMemory: {}", Runtime.getRuntime().totalMemory());
		log.info("freeMemory: {}", Runtime.getRuntime().freeMemory());
		log.info("maxMemory: {}", Runtime.getRuntime().maxMemory());
		log.info("availableProcessors: {}", Runtime.getRuntime().availableProcessors());
		log.info("totalCommittedMemory: {}", getTotalCommittedMemory());
		log.info("committedHeap: {}", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
		log.info("totalUsedMemory: {}", getTotalUsedMemory());
		log.info("usedHeap: {}", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
	}

	protected static void logWelcome(String benchmarkType, boolean withTransactions, int size) {
		String endStars = "      ***";
		if (size < 1000000) endStars += "*";
		if (size < 100000) endStars += "*";
		if (size < 10000) endStars += "*";
		if (size < 1000) endStars += "*";
		if (size < 100) endStars += "*";
		if (size < 10) endStars += "*";


		logJvmSettings();

		logStars(false);
		logStars(true);
		log.info("*****               !!! WELCOME TO ENTRYSTORE BENCHMARK !!!               *****");
		logStars(true);
		logStars(false);
		log.info("*****       Running Benchmark for {} storage with {} persons {} ", benchmarkType, size, endStars);
		log.info("*****                         Transactions are {}                       ******", withTransactions ? "ON " : "OFF");
		logStars(false);
	}

	protected static void logGoodbye() {
		logStars(false);
		logStars(false);
		logStars(true);
		log.info("*****              !!!             GOODBYE             !!!                *****");
		logStars(true);
		logStars(false);
		logStars(false);
	}

	public static void logType(String operationType) {
		logStars(false);
		log.info("***********                     {} DATA                       ***********", operationType);
		logStars(false);
	}

	public static void logDate(String message, LocalDateTime date) {
		log.info("{} {}", message, dateTimeFormatter.format(date));
	}

	public static void logTimeDifference(String message, LocalDateTime start, LocalDateTime end) {
		log.info("{} {} milliseconds.", message, Duration.between(start, end).toMillis());
	}

	public static void log(String message) {
		log.info(message);
	}

}
