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

import io.github.shiftinglabs.codej.CodingTools;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Immutable direct DeepSeek API configuration; no environment credentials or CLI settings are inferred. */
public final class DeepSeekApiConfig
{
    final String model;
    final Supplier<String> key;
    final URI baseUrl;
    final Duration timeout;
    final int maximumOutputTokens;
    final Optional<Executor> callbacks;
    final Optional<CodingTools> codingTools;

    private DeepSeekApiConfig(Builder builder)
    {
        model = builder.model;
        key = builder.key;
        baseUrl = builder.baseUrl;
        timeout = builder.timeout;
        maximumOutputTokens = builder.maximumOutputTokens;
        callbacks = builder.callbacks;
        codingTools = builder.codingTools;
    }

    public static Builder builder(String model, Supplier<String> key) { return new Builder(model, key); }

    public static final class Builder
    {
        private final String model;
        private final Supplier<String> key;
        private URI baseUrl = URI.create("https://api.deepseek.com");
        private Duration timeout = Duration.ofMinutes(2);
        private int maximumOutputTokens = 4096;
        private Optional<Executor> callbacks = Optional.empty();
        private Optional<CodingTools> codingTools = Optional.empty();

        private Builder(String model, Supplier<String> key)
        {
            this.model = checkText(model, "model");
            checkArgument(model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"), "invalid model identifier");
            this.key = requireNonNull(key, "key is null");
        }

        public Builder baseUrl(URI value)
        {
            baseUrl = ApiHttpTransport.checkBaseUrl(value);
            return this;
        }

        public Builder requestTimeout(Duration value)
        {
            requireNonNull(value, "timeout is null");
            checkArgument(!value.isNegative() && !value.isZero() && value.compareTo(Duration.ofHours(1)) <= 0, "invalid API timeout");
            timeout = value;
            return this;
        }

        public Builder maximumOutputTokens(int value)
        {
            checkArgument(value > 0, "maximumOutputTokens must be positive");
            maximumOutputTokens = value;
            return this;
        }

        public Builder callbackExecutor(Executor value)
        {
            callbacks = Optional.of(requireNonNull(value, "executor is null"));
            return this;
        }

        public Builder codingTools(CodingTools value)
        {
            codingTools = Optional.of(requireNonNull(value, "codingTools is null"));
            return this;
        }

        public DeepSeekApiConfig build() { return new DeepSeekApiConfig(this); }
    }
}
