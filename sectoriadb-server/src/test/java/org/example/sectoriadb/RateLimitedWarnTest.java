package org.example.sectoriadb;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failed-authentication WARN lines are rate limited so unauthenticated garbage cannot flood the log. */
class RateLimitedWarnTest {

    @Test
    void onlyTheFirstFewLinesPerWindowAreWrittenAndTheRestIsSummarised() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("rate-limited-warn-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AtomicLong now = new AtomicLong();
            Class<?> type = Class.forName("org.example.sectoriadb.s3.auth.RateLimitedWarn");
            Constructor<?> c = type.getDeclaredConstructor(org.slf4j.Logger.class, int.class, long.class, LongSupplier.class);
            c.setAccessible(true);
            Object limiter = c.newInstance(logger, 3, 10_000_000_000L, (LongSupplier) now::get);
            var warn = type.getDeclaredMethod("warn", String.class, Object.class);
            warn.setAccessible(true);

            for (int i = 0; i < 1000; i++) warn.invoke(limiter, "auth failed: {}", "bad " + i);
            assertEquals(3, appender.list.size());

            now.addAndGet(11_000_000_000L);   // next window: one summary line, then the budget is back
            warn.invoke(limiter, "auth failed: {}", "again");
            assertEquals(5, appender.list.size());
            assertTrue(appender.list.get(3).getFormattedMessage().startsWith("997 more failed authentication"),
                    appender.list.get(3).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
