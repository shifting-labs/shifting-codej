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

import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.antigravity.AntigravityCliDriver;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/** Creates the Antigravity CLI driver for a host-selected profile and process supervisor. */
public final class AntigravityConnector
        implements AgentConnector<AntigravityConnectorConfig>
{
    @Override
    public String id()
    {
        return AntigravityCliDriver.LANE.value();
    }

    @Override
    public Class<AntigravityConnectorConfig> configurationType()
    {
        return AntigravityConnectorConfig.class;
    }

    @Override
    public ConnectorDriver create(AntigravityConnectorConfig config, ConnectorContext context)
    {
        requireNonNull(config, "config is null");
        requireNonNull(context, "context is null");
        Path profile = context.profileDirectory().normalize();
        var permissions = PosixFilePermissions.fromString("rwx------");
        try {
            Files.createDirectories(profile, PosixFilePermissions.asFileAttribute(permissions));
            if (!Files.isDirectory(profile, NOFOLLOW_LINKS)) {
                throw new IOException("The profile must be a directory");
            }
            Files.getFileAttributeView(profile, PosixFileAttributeView.class, NOFOLLOW_LINKS).setPermissions(permissions);
        }
        catch (IOException failure) {
            throw new AgentException("Antigravity's profile directory could not be prepared: " + profile, failure);
        }
        String executable = config.executable().map(Path::toAbsolutePath).map(Path::toString).orElse("agy");
        return new AntigravityCliDriver(executable, context, config.apiKey());
    }
}
