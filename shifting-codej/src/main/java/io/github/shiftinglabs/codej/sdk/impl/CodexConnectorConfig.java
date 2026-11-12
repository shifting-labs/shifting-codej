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

import io.github.shiftinglabs.codej.cli.codex.CodexGateway;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Codex runtime settings, independent of the host's state folder, callbacks and retention policy.
 * Credentials are read by the existing driver at probe or launch time, never by this configuration
 * or its factory. When several modes are supplied, the legacy precedence is DeepSeek, gateway,
 * OpenAI key, named login, then the default login.
 */
public record CodexConnectorConfig(
        Optional<Path> executable,
        Optional<String> account,
        Optional<CodexGateway> gateway,
        Optional<Supplier<String>> deepSeekKey,
        Optional<Supplier<String>> apiKey)
{
    public CodexConnectorConfig
    {
        requireNonNull(executable, "executable is null");
        requireNonNull(account, "account is null");
        requireNonNull(gateway, "gateway is null");
        requireNonNull(deepSeekKey, "deepSeekKey is null");
        requireNonNull(apiKey, "apiKey is null");
    }
}
