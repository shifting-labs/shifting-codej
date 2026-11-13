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

import io.github.shiftinglabs.codej.cli.api.ApiConnector;
import io.github.shiftinglabs.codej.cli.api.ApiEngineConfig;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestApiConnector;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

class TestApiConnector
        extends AbstractTestApiConnector<ApiEngineConfig>
{
    @Override
    protected AgentConnector<ApiEngineConfig> connector() { return new ApiConnector("contract-api"); }

    @Override
    protected ApiEngineConfig configuration()
    {
        return ApiEngineConfig.builder(new ConnectorIdentity("contract-api", "profile"), new AgentLaneId("contract-api"), "model", FakeClient::new).build();
    }

    private static final class FakeClient
            implements ModelClient
    {
        @Override
        public ModelCapabilities capabilities(String model) { return new ModelCapabilities(true, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()); }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            return new AgentProviderStatus(new AgentLaneId("contract-api"), AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none());
        }

        @Override
        public ModelCall generate(ModelRequest request, ModelEventSink sink)
        {
            sink.textDelta("Hello");
            boolean tools = request.input() instanceof ModelInput.UserMessage && !request.tools().isEmpty();
            ModelResponse response = new ModelResponse("Hello", tools ? List.of(new ModelResponse.ToolCall("call-1", "contract_lookup", "{\"key\":\"CONTRACT-1\"}")) : List.of(),
                    tools ? ModelResponse.StopReason.TOOL_CALLS : ModelResponse.StopReason.COMPLETED,
                    new ModelReplayState("contract-api", 1, request.replayState().map(ModelReplayState::payload).orElse("") + "Hello"), Optional.empty());
            return new ModelCall()
            {
                @Override
                public CompletionStage<ModelResponse> completion() { return CompletableFuture.completedFuture(response); }

                @Override
                public void cancel() {}
            };
        }

        @Override
        public void close() {}
    }
}
