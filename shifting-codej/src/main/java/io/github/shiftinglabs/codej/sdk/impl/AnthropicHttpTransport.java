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

/** Adapts the SDK HTTP extension point to the shared bounded transport. SDK retries are disabled by the model client. */
final class AnthropicHttpTransport
        implements HttpClient
{
    private final ApiHttpTransport transport;
    private final Duration timeout;

    AnthropicHttpTransport(Duration timeout)
    {
        this.timeout = timeout;
        transport = new ApiHttpTransport(timeout);
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options)
    {
        try {
            Duration budget = options.timeout().map(value -> value.request()).orElse(timeout);
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
            }
            builder.method(request.method().toString(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
            var reply = transport.execute(builder.build(), budget);
            return new HttpResponse()
            {
                @Override
                public int statusCode() { return reply.statusCode(); }

                @Override
                public Headers headers() { return Headers.builder().putAll(reply.headers()).build(); }

                @Override
                public InputStream body() { return reply.body(); }

                @Override
                public void close() { reply.close(); }
            };
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
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Async transport is not used"));
    }

    @Override
    public void close() { transport.close(); }
}
