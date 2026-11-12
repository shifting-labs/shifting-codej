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
package io.github.shiftinglabs.codej.cli.spi.model;

import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Provider adapter for individual inference requests, below the agent connector SPI. A shared
 * agent engine owns sessions, persistence, permissions and tool execution. This adapter never
 * invokes host tools or runs an agent loop. Provider SDK objects do not cross this boundary.
 *
 * <p>A client is thread-safe and may serve independent calls concurrently. Construction makes no
 * inference request. Credentials belong to its configuration, never to requests or replay state.
 */
public interface ModelClient
        extends AutoCloseable
{
    /** Side-effect-free description of the selected model. Unsupported models must be rejected. */
    ModelCapabilities capabilities(String model);

    /** Checks readiness without inference or tool execution; may contact a provider metadata endpoint. */
    AgentProviderStatus probe(Optional<String> model);

    /** Optional provider catalog; an empty list means enumeration is unavailable. Never performs inference. */
    default List<ProviderModel> models()
    {
        return List.of();
    }

    /** Optional account limits, independent of token usage. Never performs inference. */
    default ProviderQuotaSnapshot quota()
    {
        return ProviderQuotaSnapshot.unavailable("This model provider does not report account limits.");
    }

    /**
     * Starts one model response, never an entire public agent turn. Reject null arguments, invalid
     * replay ownership/version, unsupported options and expired deadlines before submission.
     * Stream only displayable text, in order, before completing the call; do not expose reasoning,
     * credentials, SDK payloads or partial tool arguments. Sink failures stop the call.
     *
     * <p>Do not silently retry after visible output or uncertain delivery. A synchronous failure
     * uses {@link ModelClientException}; once a handle exists its completion reports the outcome.
     */
    ModelCall generate(ModelRequest request, ModelEventSink events);

    /**
     * Idempotently stops owned calls and closes owned transports. New calls after close fail.
     * Never closes borrowed executors/services or deletes saved history. An unverified shutdown
     * must be reported as a failure. Local cancellation cannot promise remote computation stopped.
     */
    @Override
    void close();
}
