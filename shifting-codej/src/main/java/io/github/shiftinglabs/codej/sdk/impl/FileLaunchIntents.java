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

import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.cli.process.LaunchIntentRecorder;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Keeps each launch's ownership record as one small file, written and synced before the supervisor goes on. */
final class FileLaunchIntents
        implements LaunchIntentRecorder
{
    private static final Pattern LAUNCH_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final String PREPARED = "prepared";
    private static final String ATTEMPTED = "attempted";

    private final Path directory;

    FileLaunchIntents(Path directory)
    {
        this.directory = requireNonNull(directory, "directory is null");
    }

    @Override
    public void prepared(String launchId, ProcessOwner owner, String executable, String argvDigest)
    {
        write(launchId, PREPARED);
    }

    @Override
    public void attempting(String launchId)
    {
        write(launchId, ATTEMPTED);
    }

    @Override
    public Map<String, Boolean> committedLaunches()
    {
        try (Stream<Path> files = Files.list(directory)) {
            ImmutableMap.Builder<String, Boolean> launches = ImmutableMap.builder();
            for (Path file : files.filter(path -> LAUNCH_ID.matcher(path.getFileName().toString()).matches()).toList()) {
                launches.put(file.getFileName().toString(), Files.readString(file).equals(ATTEMPTED));
            }
            return launches.buildOrThrow();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void forget(String launchId)
    {
        try {
            Files.deleteIfExists(file(launchId));
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void write(String launchId, String state)
    {
        Path target = file(launchId);
        Path temporary = directory.resolve(launchId + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(StandardCharsets.UTF_8.encode(state));
            channel.force(true);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path file(String launchId)
    {
        checkArgument(LAUNCH_ID.matcher(launchId).matches(), "Unexpected launch id: %s", launchId);
        return directory.resolve(launchId);
    }
}
