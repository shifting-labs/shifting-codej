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

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import java.nio.file.Path;
import java.util.List;

/**
 * One acquired session writer. Save acknowledges a durable commit before publishing its snapshot.
 * A failed save retains the previous in-memory snapshot; callers must treat the disk outcome as uncertain,
 * except for {@link CheckpointFull}, which is refused before anything is written.
 */
interface ApiSessionRepository
        extends AutoCloseable
{
    /**
     * The snapshot, or the snapshot after every started tool settles with {@link ApiCheckpoint#UNRECORDED_RESULT},
     * exceeds the checkpoint budget. Nothing was written; the previous snapshot remains committed.
     */
    final class CheckpointFull
            extends AgentUnavailableException
    {
        CheckpointFull() { super("API session checkpoint is full; start a new session, or enable context compaction or audit retention to keep sessions below the limit"); }
    }

    @FunctionalInterface
    interface Factory
    {
        /** Validate session/version/profile ownership and acquire a writer; failed open releases all acquired claims. */
        ApiSessionRepository open(Path profile, String id, boolean resume, ApiEngineConfig config);
    }

    /** The immutable last acknowledged snapshot; callers cannot mutate the repository by retaining it. */
    ApiCheckpoint state();

    /** Reject changes to session identity, connector, profile or lane before attempting a commit. */
    void save(ApiCheckpoint next);

    /** Stage private, durable, bounded attachment copies and return their provider-neutral references. */
    ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments);

    /** Optional owned-file cleanup after a committed retention decision; protect in-flight fresh attachments. */
    default int pruneAttachments(int maximumDeletions, List<AgentAttachment> protectedAttachments, Runnable checkStop) { return 0; }

    /** Release the writer only after its owner has confirmed that all local work stopped. */
    @Override
    void close();
}
