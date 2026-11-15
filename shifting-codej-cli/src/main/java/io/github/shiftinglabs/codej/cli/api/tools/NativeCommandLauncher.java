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
package io.github.shiftinglabs.codej.cli.api.tools;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JRE-only sandbox entry point. No request is delivered until the controller verifies kernel isolation. */
public final class NativeCommandLauncher
{
    static final int MAGIC = 0x434A4E31;
    static final int COMMIT = 0x5A;
    static final int MAX_BYTES = 160 * 1024;

    private NativeCommandLauncher() {}

    record Request(List<String> argv, String directory)
    {
        Request
        {
            argv = List.copyOf(argv);
            if (argv.isEmpty() || argv.size() > 128 || argv.stream().anyMatch(value -> value.isBlank() || value.indexOf('\0') >= 0)
                    || argv.stream().mapToLong(String::length).sum() > 32768 || !Path.of(argv.getFirst()).isAbsolute()) {
                throw new IllegalArgumentException("Invalid native command argv");
            }
            Path path = Path.of(directory);
            if (directory.isBlank() || directory.length() > 4096 || path.isAbsolute() || directory.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid native command directory");
            }
            for (Path part : path) {
                if (part.toString().equals("..")) {
                    throw new IllegalArgumentException("Invalid native command directory");
                }
            }
        }

        @Override
        public String toString() { return "NativeCommandRequest[private]"; }
    }

    static byte[] encode(String nonce, Request request)
            throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            write(out, nonce);
            out.writeInt(request.argv().size());
            for (String arg : request.argv()) {
                write(out, arg);
            }
            write(out, request.directory());
        }
        if (bytes.size() > MAX_BYTES) {
            throw new IOException("Native command frame is too large");
        }
        return bytes.toByteArray();
    }

    static Request read(DataInputStream input, String nonce)
            throws IOException
    {
        if (input.readInt() != MAGIC || !read(input, 64).equals(nonce)) {
            throw new IOException("Invalid native command frame");
        }
        int count = input.readInt();
        if (count < 1 || count > 128) {
            throw new IOException("Invalid native command frame");
        }
        List<String> argv = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < count; i++) {
            String arg = read(input, 98304);
            total += arg.length();
            if (total > 32768) {
                throw new IOException("Native command frame is too large");
            }
            argv.add(arg);
        }
        String directory = read(input, 16384);
        if (input.read() != COMMIT || input.read() != -1) {
            throw new IOException("Native command was not committed");
        }
        return new Request(argv, directory);
    }

    private static void write(DataOutputStream out, String value)
            throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String read(DataInputStream input, int limit)
            throws IOException
    {
        int length = input.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("Native command field is too large");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Incomplete native command frame");
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /** Announces readiness, then requires a complete committed request before starting the payload. */
    public static void main(String[] args)
    {
        try {
            if (args.length != 1 || !args[0].matches("[a-f0-9-]{36}")) {
                throw new IOException("Invalid launcher identity");
            }
            System.out.println("codej-native-ready:" + args[0]);
            System.out.flush();
            Request request = read(new DataInputStream(System.in), args[0]);
            var builder = new ProcessBuilder(request.argv()).directory(Path.of("/workspace").resolve(request.directory()).toFile()).inheritIO();
            builder.environment().clear();
            builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "HOME", "/tmp", "TMPDIR", "/tmp", "LANG", "C", "LC_ALL", "C"));
            int exit = builder.start().waitFor();
            System.exit(exit);
        }
        catch (Exception failure) {
            System.err.println("Native command could not start");
            System.exit(126);
        }
    }
}
