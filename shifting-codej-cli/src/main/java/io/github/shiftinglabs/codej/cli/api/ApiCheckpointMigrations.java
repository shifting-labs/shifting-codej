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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/** Pure, explicit format upgrades. Ownership and durable replacement remain the repository's responsibility. */
final class ApiCheckpointMigrations
{
    private ApiCheckpointMigrations() {}

    static ModelHistory history(int sourceVersion, Optional<ModelHistory> saved, ApiCheckpoint.Phase phase,
            List<ApiJournal.Entry> journal, Optional<ModelReplayState> replay)
    {
        return switch (sourceVersion) {
            case 1 -> {
                checkArgument(saved.isEmpty(), "version-1 checkpoints cannot contain version-2 history");
                // Old response events contain stop reasons, not assistant text or complete calls.
                boolean unknownPrefix = phase != ApiCheckpoint.Phase.READY || replay.isPresent() || journal.stream().anyMatch(entry ->
                        entry instanceof ApiJournal.Request || entry instanceof ApiJournal.Response
                                || entry instanceof ApiJournal.ToolStarted || entry instanceof ApiJournal.ToolResult);
                yield new ModelHistory(!unknownPrefix, List.of());
            }
            case ApiCheckpoint.CURRENT_VERSION -> {
                var history = saved.orElseThrow(() -> new IllegalArgumentException("version-2 history is required"));
                checkArgument(history.summarizedThroughRequestId().isEmpty(), "version-2 history cannot have a retained prefix");
                yield history;
            }
            case ApiCheckpoint.RETENTION_VERSION -> {
                var history = saved.orElseThrow(() -> new IllegalArgumentException("version-3 history is required"));
                checkArgument(history.summarizedThroughRequestId().isPresent(), "version-3 retained prefix is required");
                yield history;
            }
            default -> throw new IllegalArgumentException("unsupported checkpoint version");
        };
    }
}
