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

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** The SDK's HTTP extension point backed by JDK HTTP; redirects are disabled, and the adapter disables SDK retries. */
final class AnthropicHttpTransport
        implements HttpClient
{
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("codej-anthropic-http-", 0).factory());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("codej-anthropic-deadline").factory());
    private final java.net.http.HttpClient client;
    private final Set<Response> responses = ConcurrentHashMap.newKeySet();
    private final Duration timeout;
    private volatile boolean closed;

    AnthropicHttpTransport(Duration timeout)
    {
        this.timeout = timeout;
        client = java.net.http.HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).version(java.net.http.HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options)
    {
        if (closed) {
            throw new AnthropicIoException("API transport is closed");
        }
        try {
            Duration budget = options.timeout().map(value -> value.request()).orElse(timeout);
            long started = System.nanoTime();
            var builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(budget);
            for (String name : request.headers().names()) {
                if (!Set.of("content-length", "host", "connection").contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    request.headers().values(name).forEach(value -> builder.header(name, value));
                }
            }
            byte[] body = new byte[0];
            if (request.body().isPresent()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                request.body().orElseThrow().writeTo(bytes);
                body = bytes.toByteArray();
                if (body.length > 32 * 1024 * 1024) {
                    throw new IOException("request body exceeds limit");
                }
            }
            builder.method(request.method().toString(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
            var reply = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            Response response = new Response(reply);
            responses.add(response);
            try {
                response.deadline = deadlines.schedule(response::close, Math.max(1, budget.toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            }
            catch (java.util.concurrent.RejectedExecutionException failure) {
                response.close();
                throw new IOException("transport closed during request");
            }
            if (closed) {
                response.close();
                throw new IOException("transport closed during request");
            }
            return response;
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AnthropicIoException("API request interrupted");
        }
        catch (IOException failure) {
            throw new AnthropicIoException("API transport failed");
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options)
    {
        // The adapter deliberately uses the SDK's synchronous streaming path on its own bounded workers.
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Async transport is not used"));
    }

    @Override
    public void close()
    {
        closed = true;
        responses.forEach(Response::close);
        client.shutdownNow();
        executor.shutdown();
        deadlines.shutdownNow();
        try {
            if (!client.awaitTermination(Duration.ofSeconds(2)) || !executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new AnthropicIoException("API transport shutdown is unconfirmed");
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AnthropicIoException("API transport shutdown interrupted");
        }
    }

    private final class Response
            implements HttpResponse
    {
        private final java.net.http.HttpResponse<InputStream> response;
        private volatile ScheduledFuture<?> deadline;

        Response(java.net.http.HttpResponse<InputStream> response) { this.response = response; }

        @Override
        public int statusCode() { return response.statusCode(); }

        @Override
        public Headers headers() { return Headers.builder().putAll(response.headers().map()).build(); }

        @Override
        public InputStream body() { return response.body(); }

        @Override
        public void close()
        {
            try {
                ScheduledFuture<?> pending = deadline;
                if (pending != null) {
                    pending.cancel(false);
                }
                response.body().close();
                responses.remove(this);
            }
            catch (IOException failure) {
                throw new AnthropicIoException("API response shutdown failed");
            }
        }
    }
}
