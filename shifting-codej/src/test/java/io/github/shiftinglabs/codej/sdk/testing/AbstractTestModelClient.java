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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable adapter contracts. Bind a client to a deterministic in-memory or fake HTTP fixture,
 * never credentials, native CLIs or a live model. Provider-specific tests verify wire encoding.
 */
public abstract class AbstractTestModelClient
{
    /** A fresh client for each test, with its fixture configured for the hooks below. */
    protected abstract ModelClient client();

    protected abstract ModelRequest request();

    protected abstract ModelResponse expectedResponse();

    protected abstract List<String> expectedTextDeltas();

    /** Arrange a request held pending until cancellation; invoke generate through the public SPI. */
    protected abstract ModelCall pendingCall(ModelClient client);

    /** Arrange a lost connection after possible acceptance, reported through completion. */
    protected abstract ModelCall failedCall(ModelClient client);

    @Test
    protected void exposesModelMetadataWithoutRequiringOptionalCatalogOrQuota()
    {
        try (ModelClient client = client()) {
            assertThat(client.capabilities(request().model())).isNotNull();
            assertThat(client.probe(Optional.empty()).available()).isTrue();
            assertThat(client.probe(Optional.of(request().model())).available()).isTrue();
            assertThat(client.models()).isNotNull();
            assertThat(client.quota()).isNotNull();
        }
    }

    @Test
    protected void streamsInOrderThenReturnsOneCompleteResponse()
            throws Exception
    {
        try (ModelClient client = client()) {
            List<String> deltas = new ArrayList<>();
            ModelCall call = client.generate(request(), deltas::add);
            ModelResponse response = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            ModelResponse expected = expectedResponse();
            assertThat(deltas).containsExactlyElementsOf(expectedTextDeltas());
            assertThat(response.text()).isEqualTo(expected.text());
            assertThat(response.toolCalls()).isEqualTo(expected.toolCalls());
            assertThat(response.stopReason()).isEqualTo(expected.stopReason());
            assertThat(response.replayState().providerId()).isEqualTo(expected.replayState().providerId());
            assertThat(response.replayState().formatVersion()).isEqualTo(expected.replayState().formatVersion());
            assertThat(response.replayState().payload()).isEqualTo(expected.replayState().payload());
            assertThat(response.usage()).isEqualTo(expected.usage());
            call.cancel();
            call.cancel();
            assertThat(call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS)).isSameAs(response);
            assertThat(deltas).containsExactlyElementsOf(expectedTextDeltas());
        }
    }

    @Test
    protected void cancellationSettlesPendingCallsAndIsIdempotent()
            throws Exception
    {
        try (ModelClient client = client()) {
            ModelCall call = pendingCall(client);
            assertThat(call.completion().toCompletableFuture()).isNotDone();
            call.cancel();
            call.cancel();
            assertThat(failure(call)).isInstanceOf(CancellationException.class);
        }
    }

    @Test
    protected void normalizedFailuresKeepTheirDeliveryUncertainty()
            throws Exception
    {
        try (ModelClient client = client()) {
            Throwable failure = failure(failedCall(client));
            assertThat(failure).isInstanceOf(ModelClientException.class);
            ModelClientException normalized = (ModelClientException) failure;
            assertThat(normalized.kind()).isEqualTo(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE);
            assertThat(normalized.deliveryUncertain()).isTrue();
            assertThat(normalized.getMessage()).isNotBlank();
        }
    }

    @Test
    protected void closeStopsOwnedCallsAndRefusesNewWork()
            throws Exception
    {
        ModelClient client = client();
        try {
            ModelCall call = pendingCall(client);
            client.close();
            client.close();
            assertThat(failure(call)).isInstanceOf(CancellationException.class);
            assertThatThrownBy(() -> client.generate(request(), text -> {})).isInstanceOf(ModelClientException.class);
        }
        finally {
            client.close();
        }
    }

    @Test
    protected void cancellingOneCallLeavesAnotherIndependentCallPending()
            throws Exception
    {
        try (ModelClient client = client()) {
            ModelCall first = pendingCall(client);
            ModelCall second = pendingCall(client);
            assertThat(second).isNotSameAs(first);
            first.cancel();
            assertThat(failure(first)).isInstanceOf(CancellationException.class);
            assertThat(second.completion().toCompletableFuture()).isNotDone();
            second.cancel();
            assertThat(failure(second)).isInstanceOf(CancellationException.class);
        }
    }

    @Test
    protected void rejectsNullBoundaryInputs()
    {
        try (ModelClient client = client()) {
            assertThatThrownBy(() -> client.capabilities(null)).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
            assertThatThrownBy(() -> client.probe(null)).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
            assertThatThrownBy(() -> client.generate(null, text -> {})).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> client.generate(request(), null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    protected void eventConsumerFailureFailsTheCall()
            throws Exception
    {
        try (ModelClient client = client()) {
            ModelCall call = client.generate(request(), text -> { throw new IllegalStateException("Consumer failed"); });
            assertThat(failure(call)).isInstanceOf(ModelClientException.class);
        }
    }

    private static Throwable failure(ModelCall call)
            throws Exception
    {
        Throwable failure = call.completion().handle((response, error) -> error).toCompletableFuture().get(3, TimeUnit.SECONDS);
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
