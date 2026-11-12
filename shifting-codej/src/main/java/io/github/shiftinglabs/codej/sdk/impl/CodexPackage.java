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

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Codex;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static io.github.shiftinglabs.codej.sdk.impl.CodexBinary.FILES;
import static java.lang.Math.toIntExact;

/** Extracts and verifies the files Codex needs from its pinned npm package. */
final class CodexPackage
{
    private CodexPackage() {}

    /** Takes the four files from the npm package, which keeps them under {@code package/vendor/<target>/}. */
    static void extract(Path archive, Path image, List<String> expected, boolean arm)
            throws IOException
    {
        String root = "package/vendor/" + (arm ? "aarch64-apple-darwin" : "x86_64-apple-darwin") + "/";
        Set<String> found = new HashSet<>();
        try (InputStream tar = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            Optional<String> paxPath = Optional.empty();
            while (true) {
                int read = tar.readNBytes(header, 0, header.length);
                if (read == 0 || isZero(header)) {
                    break;
                }
                if (read < header.length) {
                    throw new AgentException("The Codex package ends inside an entry");
                }
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                String name = paxPath.orElseGet(() -> name(header));
                paxPath = Optional.empty();
                if (type == 'x') {
                    paxPath = path(new String(tar.readNBytes(toIntExact(size)), StandardCharsets.UTF_8));
                    tar.skipNBytes(padding(size));
                    continue;
                }
                int index = name.startsWith(root) ? FILES.indexOf(name.substring(root.length())) : -1;
                if (index < 0) {
                    tar.skipNBytes(size + padding(size));
                    continue;
                }
                String file = FILES.get(index);
                if ((type != '0' && type != '\0') || !found.add(file)) {
                    throw new AgentException("The Codex package's " + file + " is not one regular file");
                }
                Path destination = image.resolve(file);
                Files.createDirectories(destination.getParent());
                try (HashingInputStream hashing = new HashingInputStream(Hashing.sha256(), new BoundedInputStream(tar, size))) {
                    Files.copy(hashing, destination);
                    if (!hashing.hash().toString().equals(expected.get(index))) {
                        throw new AgentException("The Codex package's " + file + " does not match the checksum this library records for Codex " + Codex.VERSION);
                    }
                }
                tar.skipNBytes(padding(size));
                if (!file.endsWith(".json")) {
                    Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r-x------"));
                }
            }
        }
        if (found.size() != FILES.size()) {
            throw new AgentException("The Codex package has no " + FILES.stream().filter(file -> !found.contains(file)).findFirst().orElseThrow());
        }
    }

    private static String name(byte[] header)
    {
        String name = text(header, 0, 100);
        String prefix = text(header, 257, 6).startsWith("ustar") ? text(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** A pax header's {@code path} record, which names the entry after it. */
    private static Optional<String> path(String records)
    {
        for (String record : records.split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                return Optional.of(record.substring(space + 6));
            }
        }
        return Optional.empty();
    }

    private static long octal(byte[] header, int offset, int length)
    {
        String text = text(header, offset, length).strip();
        if ((header[offset] & 0x80) != 0 || text.isEmpty()) {
            throw new AgentException("The Codex package has an entry of unreadable size");
        }
        return Long.parseLong(text, 8);
    }

    private static String text(byte[] header, int offset, int length)
    {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] header)
    {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static long padding(long size)
    {
        return (512 - size % 512) % 512;
    }

    /** Reads at most {@code remaining} bytes of the archive, the one entry being copied. */
    private static final class BoundedInputStream
            extends InputStream
    {
        private final InputStream source;
        private long remaining;

        BoundedInputStream(InputStream source, long remaining)
        {
            this.source = source;
            this.remaining = remaining;
        }

        @Override
        public int read()
                throws IOException
        {
            if (remaining == 0) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new AgentException("The Codex package ends inside an entry");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException
        {
            if (remaining == 0) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new AgentException("The Codex package ends inside an entry");
            }
            remaining -= read;
            return read;
        }

        /** The archive stays open for the entries after this one. */
        @Override
        public void close() {}
    }
}
