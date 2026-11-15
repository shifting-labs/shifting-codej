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

import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Local provider extension boundary; callback diagnostics and projected contents never escape. */
final class ContextSizing
{
    private ContextSizing() {}

    static Optional<ModelContextAssessment> assess(Optional<ModelContextSizer> sizer, String provider, ModelRequest request, Supplier<String> projection)
    {
        if (sizer.isEmpty()) {
            return Optional.empty();
        }
        try {
            checkActive(request);
            var input = new ModelContextInput(provider, request.model(), projection.get(), request.maximumOutputTokens(), request.deadline());
            checkActive(request);
            var result = requireNonNull(sizer.orElseThrow().assess(input), "sizer returned null");
            checkActive(request);
            return result;
        }
        catch (CancellationException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "Provider context sizing failed", false);
        }
    }

    private static void checkActive(ModelRequest request)
    {
        if (Thread.currentThread().isInterrupted() || request.deadline().filter(deadline -> !deadline.isAfter(Instant.now())).isPresent()) {
            throw new CancellationException("Context sizing cancelled or deadline exceeded");
        }
    }
}
