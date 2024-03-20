package org.entrystore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogUtils {


    private static final Logger log = LoggerFactory.getLogger(Benchmark.class);

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    protected void logDate(String benchmarkType, String dateType, LocalDateTime date) {
        log.info("======================================================================");
        log.info("Benchmark for " + benchmarkType + " storage " + dateType + "ing at " + dateTimeFormatter.format(date));
        log.info("======================================================================");
    }

    protected void logTimeDifference(String benchmarkType, LocalDateTime start, LocalDateTime end) {
        log.info("======================================================================");
        log.info("Benchmark for " + benchmarkType + " storage time elapsed: " + Duration.between(start, end).toMillis() + " milliseconds.");
        log.info("======================================================================");
    }
}
