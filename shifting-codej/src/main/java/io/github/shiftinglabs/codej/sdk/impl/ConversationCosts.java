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
package io.github.shiftinglabs.codej.sdk.impl;

import com.google.common.hash.Hashing;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * The last cost total the provider reported for each conversation. Some Claude releases count a conversation's cost across resumes,
 * and Claude starts a process for each turn, so a turn's own cost is what that total grew by since the conversation's previous report,
 * which may have come before this program started.
 */
final class ConversationCosts
{
    private static final System.Logger log = System.getLogger(ConversationCosts.class.getName());
    /** Totals arrive as decimal dollars, so a total a hair below the last one is rounding, not a new count. */
    private static final BigDecimal ROUNDING = new BigDecimal("0.000001");

    private final Path folder;

    ConversationCosts(Path folder)
    {
        this.folder = requireNonNull(folder, "folder is null");
    }

    /**
     * Records {@code total} as what the conversation has cost so far.
     *
     * @return what the total grew by since the conversation's previous report; empty when it fell, as when the provider began
     *         counting again, or when the previous report could not be read
     */
    // ponytail: one small file per conversation, never pruned; prune with the provider's own transcripts if the folder ever grows large.
    synchronized Optional<BigDecimal> turnCost(String conversation, BigDecimal total)
    {
        Path file = folder.resolve(Hashing.sha256().hashString(conversation, UTF_8).toString());
        Optional<BigDecimal> before = read(file);
        write(file, total);
        return before.map(total::subtract)
                .filter(grown -> grown.compareTo(ROUNDING.negate()) >= 0)
                .map(grown -> grown.max(BigDecimal.ZERO));
    }

    private static Optional<BigDecimal> read(Path file)
    {
        try {
            return Optional.of(new BigDecimal(Files.readString(file).strip()));
        }
        catch (NoSuchFileException first) {
            return Optional.of(BigDecimal.ZERO);
        }
        catch (IOException | NumberFormatException failure) {
            log.log(System.Logger.Level.WARNING, "Could not read the conversation's last cost total " + file, failure);
            return Optional.empty();
        }
    }

    private static void write(Path file, BigDecimal total)
    {
        try {
            Path written = Files.writeString(file.resolveSibling(file.getFileName() + ".tmp"), total.toPlainString());
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException failure) {
            log.log(System.Logger.Level.WARNING, "Could not save the conversation's cost total " + file, failure);
        }
    }
}
