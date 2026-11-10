/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.shiftinglabs.codej.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

final class TestLog
{
    @Test
    void fillsPlaceholdersAndLogsATrailingFailure()
    {
        Logger jdk = Logger.getLogger("io.github.shiftinglabs.codej.cli.TestLog.fills");
        List<LogRecord> records = capture(jdk);
        Log log = Log.named(jdk.getName());
        IllegalStateException failure = new IllegalStateException("boom");

        log.warn("task {} turn {} isn't done: {'quoted'}", "t-1", 2, failure);
        log.warn("one {} and a spare {}", "value");

        assertThat(records).extracting(LogRecord::getMessage).containsExactly("task t-1 turn 2 isn't done: {'quoted'}", "one value and a spare {}");
        assertThat(records.get(0).getThrown()).isSameAs(failure);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(records.get(1).getThrown()).isNull();
    }

    @Test
    void buildsNoMessageBelowTheLoggerLevel()
    {
        Logger jdk = Logger.getLogger("io.github.shiftinglabs.codej.cli.TestLog.quiet");
        jdk.setLevel(Level.INFO);
        List<LogRecord> records = capture(jdk);
        Log log = Log.named(jdk.getName());

        log.debug("hidden {}", new Object()
        {
            @Override
            public String toString()
            {
                throw new AssertionError("formatted below the logger's level");
            }
        });

        assertThat(log.isDebugEnabled()).isFalse();
        assertThat(records).isEmpty();
    }

    private static List<LogRecord> capture(Logger logger)
    {
        List<LogRecord> records = new ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return records;
    }
}
