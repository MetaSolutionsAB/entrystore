package org.entrystore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogUtils {

    public static final Logger log = LoggerFactory.getLogger(SingleTransaction.class);

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    protected static void logWelcome(String benchmarkType, boolean withTransactions, int size) {

        String endStars               = "     ***";
        if (size < 1000000) endStars += "*";
        if (size < 100000)  endStars += "*";
        if (size < 10000)   endStars += "*";
        if (size < 1000)    endStars += "*";
        if (size < 100)     endStars += "*";
        if (size < 10)      endStars += "*";

        log.info("******************************************************************************");
        log.info("******************************************************************************");
        log.info("*****                                                                    *****");
        log.info("*****              !!! WELCOME TO ENTRYSCAPE BENCHMARK !!!               *****");
        log.info("*****                                                                    *****");
        log.info("******************************************************************************");
        log.info("*****       Running Benchmark for " + benchmarkType + " storage with " + size + " persons " + endStars);
        log.info("*****                        Transactions are " + (withTransactions ? "ON " : "OFF") + "                       ******");
        log.info("******************************************************************************");
    }

    protected static void logGoodbye() {

        log.info("******************************************************************************");
        log.info("******************************************************************************");
        log.info("*****                                                                    *****");
        log.info("*****              !!!             GOODBYE             !!!               *****");
        log.info("*****                                                                    *****");
        log.info("******************************************************************************");
        log.info("******************************************************************************");
    }

    public static void logType(String operationType) {

        log.info("******************************************************************************");
        log.info("***********                     " + operationType + " DATA                      ***********");
        log.info("******************************************************************************");
    }

    public static void logDate(String message, LocalDateTime date) {
        log.info(message + " " + dateTimeFormatter.format(date));
    }

    public static void logTimeDifference(String message, LocalDateTime start, LocalDateTime end) {
        log.info(message + " " + Duration.between(start, end).toMillis() + " milliseconds.");
    }
}
