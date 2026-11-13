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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Provider-neutral owned HTTP resources, redirect policy and whole-response deadlines. No inference retries. */
final class ApiHttpTransport
        implements AutoCloseable
{
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("codej-api-http-", 0).factory());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("codej-api-http-deadline").factory());
    private final HttpClient client;
    private final Set<Response> responses = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ApiHttpTransport(Duration timeout)
    {
        client = HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    static URI checkBaseUrl(URI value)
    {
        requireNonNull(value, "baseUrl is null");
        String host = value.getHost();
        boolean local = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host);
        checkArgument(value.isAbsolute() && host != null && value.getUserInfo() == null && value.getQuery() == null && value.getFragment() == null
                && ("https".equals(value.getScheme()) || local && "http".equals(value.getScheme())), "invalid API base URL");
        return value;
    }

    Response execute(HttpRequest request, Duration budget)
            throws IOException, InterruptedException
    {
        if (closed) {
            throw new IOException("API transport is closed");
        }
        if (request.bodyPublisher().map(value -> value.contentLength() > 32 * 1024 * 1024).orElse(false)) {
            throw new IOException("API request exceeds its body limit");
        }
        long started = System.nanoTime();
        var reply = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Response response = new Response(reply);
        responses.add(response);
        try {
            response.deadline = deadlines.schedule(response::close, Math.max(1, budget.toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        }
        catch (java.util.concurrent.RejectedExecutionException failure) {
            response.close();
            throw new IOException("API transport closed during request");
        }
        if (closed) {
            response.close();
            throw new IOException("API transport closed during request");
        }
        return response;
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
            if (!client.awaitTermination(Duration.ofSeconds(2)) || !executor.awaitTermination(2, TimeUnit.SECONDS) || !deadlines.awaitTermination(2, TimeUnit.SECONDS)) {
                throw shutdown("API transport shutdown is unconfirmed");
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw shutdown("API transport shutdown interrupted");
        }
    }

    private static ModelClientException shutdown(String detail)
    {
        return new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, detail, true);
    }

    final class Response
            implements AutoCloseable
    {
        private final HttpResponse<InputStream> response;
        private volatile ScheduledFuture<?> deadline;

        Response(HttpResponse<InputStream> response) { this.response = response; }

        int statusCode() { return response.statusCode(); }

        Map<String, java.util.List<String>> headers() { return response.headers().map(); }

        InputStream body() { return response.body(); }

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
                throw shutdown("API response shutdown failed");
            }
        }
    }
}
