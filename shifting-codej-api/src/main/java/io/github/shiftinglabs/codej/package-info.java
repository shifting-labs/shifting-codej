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
/**
 * Run Codex and Claude Code from Java through one API: sessions, turns, live events, approvals,
 * questions and tools of your own.
 *
 * <h2>Shape</h2>
 * An {@link Agent} is one provider account. It opens {@link Session}s, each a conversation in one
 * working directory. Every message you send starts a {@link Turn}, which streams {@link AgentEvent}s
 * and ends with a {@link TurnResult}.
 *
 * <h2>Rules every type follows</h2>
 * <ul>
 *   <li><b>No nulls.</b> An absent value is an {@link java.util.Optional}; passing {@code null} throws
 *       {@link NullPointerException}.</li>
 *   <li><b>Immutable values.</b> Records, and the lists, sets and maps they hold, never change after
 *       construction.</li>
 *   <li><b>Your code runs on your executor.</b> Event listeners and approval, question and tool handlers
 *       run on the agent's callback executor, never on the thread that reads the CLI, so slow code of
 *       yours cannot stall the agent. One turn's events arrive in order, one at a time. The default
 *       executor starts a virtual thread for each callback.</li>
 *   <li><b>Blocking for questions, handles for work.</b> A method that asks and answers, such as
 *       {@link Agent#status()} or {@link Session#run(Input)}, blocks, which is cheap on a virtual thread.
 *       Work that keeps running returns a {@link Turn}.</li>
 *   <li><b>A turn's future never fails.</b> {@link Turn#result()} completes with a {@link TurnResult} for
 *       every outcome. {@link AgentException} is thrown only when work cannot start at all.</li>
 *   <li><b>Safe defaults.</b> A new session only reads, every approval is denied, and a request nobody
 *       answers is denied after five minutes.</li>
 *   <li><b>Processes are owned.</b> {@code close()} on a session or an agent returns only once the CLI
 *       processes it started have exited.</li>
 * </ul>
 */
package io.github.shiftinglabs.codej;
