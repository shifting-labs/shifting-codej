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

import java.lang.System.Logger.Level;

import static java.util.Objects.requireNonNull;

/**
 * Logs through the JDK's {@link System.Logger}, so the SDK puts no logging library on its users' classpath. Messages use
 * {@code {}} placeholders, and a trailing {@link Throwable} argument is logged as the failure.
 */
public final class Log
{
    private final System.Logger logger;

    private Log(System.Logger logger)
    {
        this.logger = requireNonNull(logger, "logger is null");
    }

    public static Log of(Class<?> type)
    {
        return new Log(System.getLogger(type.getName()));
    }

    public static Log named(String name)
    {
        return new Log(System.getLogger(name));
    }

    public boolean isDebugEnabled()
    {
        return logger.isLoggable(Level.DEBUG);
    }

    public void debug(String format, Object... arguments)
    {
        log(Level.DEBUG, format, arguments);
    }

    public void info(String format, Object... arguments)
    {
        log(Level.INFO, format, arguments);
    }

    public void warn(String format, Object... arguments)
    {
        log(Level.WARNING, format, arguments);
    }

    public void error(String format, Object... arguments)
    {
        log(Level.ERROR, format, arguments);
    }

    public void log(Level level, String format, Object... arguments)
    {
        if (!logger.isLoggable(level)) {
            return;
        }
        Throwable failure = arguments.length > 0 && arguments[arguments.length - 1] instanceof Throwable thrown ? thrown : null;
        int values = failure == null ? arguments.length : arguments.length - 1;
        StringBuilder message = new StringBuilder(format.length() + 16 * values);
        int from = 0;
        for (int index = 0; index < values; index++) {
            int at = format.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            message.append(format, from, at).append(arguments[index]);
            from = at + 2;
        }
        message.append(format, from, format.length());
        // The message is complete: the JDK formats only when given parameters, so braces and quotes stay as written.
        if (failure == null) {
            logger.log(level, message.toString());
        }
        else {
            logger.log(level, message.toString(), failure);
        }
    }
}
