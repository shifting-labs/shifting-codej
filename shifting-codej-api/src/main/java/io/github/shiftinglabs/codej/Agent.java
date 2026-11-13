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
package io.github.shiftinglabs.codej;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One provider account, such as a Codex login, a Claude login or a gateway. Each provider's builder
 * makes one.
 *
 * <p>Building an agent starts no inference or CLI process. CLI agents start a process for each session;
 * direct API agents such as {@code ClaudeApi} use HTTP and keep replay locally. The same session,
 * turn and tool contracts apply. An agent is safe to share between threads and can run many sessions at once.
 */
public interface Agent
        extends AutoCloseable
{
    /**
     * Checks provider readiness. CLI agents check installation, version and sign-in; direct API
     * agents check credentials through provider metadata without inference.
     *
     * <p>Blocks while the CLI answers, usually for under a second. Never throws for a missing or
     * unready CLI: the status says what is wrong and how to fix it.
     *
     * @return what the check found
     */
    AgentStatus status();

    /**
     * Lists the models this account can use, as the provider reports them. Blocks while the provider answers.
     *
     * @return the models, in the provider's order
     * @throws AgentException when the agent is not {@linkplain AgentStatus#ready() ready}
     */
    List<Model> models();

    /**
     * Reads the account's usage limits without making a model request. Blocks while the CLI answers.
     *
     * <p>Never throws: when the provider reports no limits, or cannot be asked, the message says so.
     *
     * @return the limits
     */
    Limits limits();

    /**
     * Starts a new conversation. Blocks until its CLI process can take a first turn, usually a few
     * seconds. The agent's first session checks the CLI first, as {@link #status()} does, unless a
     * status check has already found it ready: what the CLI supports, such as steering, is known
     * only once it is checked.
     *
     * @param options where and how the session runs
     * @return the open session; close it to stop its process
     * @throws AgentException when the agent is not ready or its process cannot start
     */
    Session startSession(SessionOptions options);

    /**
     * Continues a conversation saved with {@link Session#ref()}, also after this program restarted.
     * The conversation keeps its history; {@code options} apply from its next turn.
     *
     * @param ref the saved conversation
     * @param options where and how the session runs from now on
     * @return the open session; close it to stop its process
     * @throws IllegalArgumentException when {@code ref} belongs to another provider
     * @throws AgentException when the provider no longer has the conversation or its process cannot start
     */
    Session resumeSession(SessionRef ref, SessionOptions options);

    /**
     * The working directories of sessions whose CLI process could not be shown to have stopped: ones an earlier run of this program
     * left behind, and ones of this run whose stop failed. Such a process may still change files there, so keep other work out of them.
     *
     * <p>Building the first agent on a state folder stops the processes an earlier run left in it; this lists the ones it could not.
     * Each call checks those again and leaves out the ones now shown stopped. A session of this run whose stop failed stays listed
     * until its {@link Session#close()} shows the process stopped, or the next run checks it.
     *
     * @return the directories; empty when every process is accounted for
     */
    Set<Path> unconfirmedDirectories();

    /**
     * Closes every session this agent opened and waits for their processes to exit.
     *
     * @throws AgentException when a process could not be shown to have exited
     */
    @Override
    void close();
}
