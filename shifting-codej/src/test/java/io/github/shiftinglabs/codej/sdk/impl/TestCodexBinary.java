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
import com.google.common.hash.Hashing;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexBinary
{
    private static final String CLASSIFIER = "osx-aarch_64";
    private static final String ROOT = "package/vendor/aarch64-apple-darwin/";

    @TempDir
    private Path cache;
    @TempDir
    private Path state;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void serve()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/npm/@openai/codex/-/codex-" + Codex.VERSION + "-darwin-arm64.tgz")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                responder.get().respond(exchange);
            }
        });
        server.start();
    }

    @AfterEach
    void stop()
    {
        server.stop(0);
    }

    @Test
    void downloadsOnceThenRunsTheCheckedCopy()
            throws IOException
    {
        byte[] archive = archive(files("codex"), false, false);
        responder.set(exchange -> send(exchange, archive));
        CodexBinary binary = binary(recorded(archive, files("codex")));

        Path codex = binary.install();

        assertThat(codex.getFileName().toString()).isEqualTo("codex");
        assertThat(Files.readString(codex)).isEqualTo("bin/codex of codex");
        assertThat(Files.isExecutable(codex)).isTrue();
        assertThat(codex.resolveSibling("../codex-path/rg").normalize()).hasContent("codex-path/rg of codex");
        assertThat(binary.install()).isEqualTo(codex);
        assertThat(requests).hasValue(1);
        assertThat(versionFolders()).hasSize(1);

        // A changed file in the cache is downloaded again rather than run.
        Files.setPosixFilePermissions(codex, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(codex, "changed");
        assertThat(binary.install()).isEqualTo(codex);
        assertThat(Files.readString(codex)).isEqualTo("bin/codex of codex");
        assertThat(requests).hasValue(2);
    }

    @Test
    void refusesADownloadThatIsNotTheRecordedPackage()
            throws IOException
    {
        byte[] recordedArchive = archive(files("codex"), false, false);
        byte[] served = archive(files("an older codex"), false, false);
        responder.set(exchange -> send(exchange, served));

        assertThatThrownBy(() -> binary(recorded(recordedArchive, files("codex"))).install())
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("is not Codex " + Codex.VERSION + " as this library records it");
        assertThat(versionFolders()).isEmpty();
    }

    @Test
    void refusesARecordedArchiveWhoseFileChecksumDoesNotMatch()
            throws IOException
    {
        Map<String, String> files = files("codex");
        byte[] archive = archive(files, false, false);
        Map<String, String> checksums = new LinkedHashMap<>(recorded(archive, files));
        checksums.put(CLASSIFIER + "/bin/codex", Hashing.sha256().hashString("another binary", StandardCharsets.UTF_8).toString());
        responder.set(exchange -> send(exchange, archive));

        assertThatThrownBy(() -> binary(checksums).install())
                .isInstanceOf(AgentException.class)
                .hasMessage("The Codex package's bin/codex does not match the checksum this library records for Codex " + Codex.VERSION);
        assertThat(requests).hasValue(1);
        assertThat(cache).isEmptyDirectory();
    }

    @Test
    void refusesDuplicateRequiredFilesEvenWhenTheirChecksumsMatch()
            throws IOException
    {
        Map<String, String> files = files("codex");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                entry(gzip, ROOT + file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8), '0');
            }
            entry(gzip, ROOT + "bin/codex", files.get("bin/codex").getBytes(StandardCharsets.UTF_8), '0');
            gzip.write(new byte[1024]);
        }
        byte[] archive = bytes.toByteArray();
        responder.set(exchange -> send(exchange, archive));

        assertThatThrownBy(() -> binary(recorded(archive, files)).install())
                .isInstanceOf(AgentException.class)
                .hasMessage("The Codex package's bin/codex is not one regular file");
        assertThat(requests).hasValue(1);
        assertThat(cache).isEmptyDirectory();
    }

    @Test
    void refusesAPackageMissingAFileOrHoldingALinkInItsPlace()
            throws IOException
    {
        Map<String, String> missing = files("codex");
        missing.remove("codex-path/rg");
        byte[] withoutRipgrep = archive(missing, false, false);
        responder.set(exchange -> send(exchange, withoutRipgrep));
        assertThatThrownBy(() -> binary(recorded(withoutRipgrep, files("codex"))).install())
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("has no codex-path/rg");

        byte[] linked = archive(files("codex"), true, false);
        responder.set(exchange -> send(exchange, linked));
        assertThatThrownBy(() -> binary(recorded(linked, files("codex"))).install())
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("bin/codex is not one regular file");
        assertThat(versionFolders()).isEmpty();
    }

    @Test
    void readsANameFromAPaxHeaderAndSkipsTheRestOfThePackage()
            throws IOException
    {
        byte[] archive = archive(files("codex"), false, true);
        responder.set(exchange -> send(exchange, archive));

        Path codex = binary(recorded(archive, files("codex"))).install();

        assertThat(codex.resolveSibling("../codex-path/rg").normalize()).hasContent("codex-path/rg of codex");
    }

    @Test
    void leavesNothingBehindWhenTheDownloadBreaksOff()
            throws IOException
    {
        byte[] archive = archive(files("codex"), false, false);
        responder.set(exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive, 0, archive.length / 2);
        });

        assertThatThrownBy(() -> binary(recorded(archive, files("codex"))).install())
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("could not be downloaded from " + address())
                .hasMessageContaining("executable(path)");
        assertThat(versionFolders()).isEmpty();
        try (Stream<Path> entries = Files.list(cache)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void givesUpOnADownloadThatStalls()
            throws IOException
    {
        byte[] archive = archive(files("codex"), false, false);
        responder.set(exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive, 0, 100);
            exchange.getResponseBody().flush();
            sleep(Duration.ofSeconds(5));
        });
        CodexBinary binary = new CodexBinary(cache, URI.create(registry()), CLASSIFIER, recorded(archive, files("codex")), Duration.ofMillis(500), Duration.ofMinutes(1));

        long started = System.nanoTime();
        assertThatThrownBy(binary::install)
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("no data for");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void twoProgramsAtOnceKeepOneCopy()
            throws Exception
    {
        byte[] archive = archive(files("codex"), false, false);
        responder.set(exchange -> send(exchange, archive));
        Map<String, String> recorded = recorded(archive, files("codex"));

        // Each round races two downloads into a fresh cache; the loser's rename lands on the winner's folder.
        for (int round = 0; round < 10; round++) {
            Path shared = Files.createDirectories(cache.resolve("round-" + round));
            CompletableFuture<Path> first = CompletableFuture.supplyAsync(() -> binary(recorded, shared).install());
            CompletableFuture<Path> second = CompletableFuture.supplyAsync(() -> binary(recorded, shared).install());

            assertThat(first.get()).isEqualTo(second.get());
            assertThat(Files.readString(first.get())).isEqualTo("bin/codex of codex");
            try (Stream<Path> entries = Files.list(shared)) {
                assertThat(entries).hasSize(1);
            }
        }
    }

    @Test
    void deletesReleasesNoBuildUsedForThirtyDays()
            throws IOException
    {
        Path old = Files.createDirectories(cache.resolve("0.100.0-aaaaaaaaaaaa"));
        Files.setLastModifiedTime(Files.createFile(old.resolve(".last-used")), FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Path recent = Files.createDirectories(cache.resolve("0.101.0-bbbbbbbbbbbb"));
        Files.setLastModifiedTime(Files.createFile(recent.resolve(".last-used")), FileTime.from(Instant.now().minus(Duration.ofDays(29))));
        Path leftover = Files.createDirectories(cache.resolve("0.101.0-bbbbbbbbbbbb-123"));
        Files.setLastModifiedTime(leftover, FileTime.from(Instant.now().minus(Duration.ofDays(40))));
        byte[] archive = archive(files("codex"), false, false);
        responder.set(exchange -> send(exchange, archive));

        Path codex = binary(recorded(archive, files("codex"))).install();

        assertThat(old).doesNotExist();
        assertThat(leftover).doesNotExist();
        assertThat(recent).exists();
        assertThat(codex).exists();
    }

    @Test
    void aCompanyMirrorReplacesPublicNpm()
    {
        assertThat(CodexBinary.registry(Optional.empty(), Optional.empty())).isEqualTo(CodexBinary.PUBLIC_REGISTRY);
        assertThat(CodexBinary.registry(Optional.empty(), Optional.of("https://mirror.example.com/npm"))).isEqualTo(URI.create("https://mirror.example.com/npm/"));
        assertThat(CodexBinary.registry(Optional.of("https://property.example.com/npm/"), Optional.of("https://variable.example.com/npm/")))
                .isEqualTo(URI.create("https://property.example.com/npm/"));
        assertThat(new CodexBinary(cache, URI.create("https://mirror.example.com/npm/"), CLASSIFIER, ImmutableMap.of(), Duration.ofSeconds(1), Duration.ofSeconds(1)).address())
                .isEqualTo(URI.create("https://mirror.example.com/npm/@openai/codex/-/codex-" + Codex.VERSION + "-darwin-arm64.tgz"));
        assertThatThrownBy(() -> CodexBinary.registry(Optional.of("file:///tmp/npm"), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMacWithoutARecordedReleaseIsToldToBringItsOwnCodex()
    {
        CodexBinary intel = new CodexBinary(cache, URI.create(registry()), "osx-x86_64", ImmutableMap.of(), Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(intel::install)
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("records no Codex " + Codex.VERSION + " for osx-x86_64")
                .hasMessageContaining("executable(path)");
        assertThat(requests).hasValue(0);
    }

    @Test
    void anAgentWhoseDownloadFailedSaysWhatFailedAndWhatToDo()
            throws IOException
    {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        URI mirror = URI.create("http://127.0.0.1:" + closed + "/npm/");
        CodexBinary unreachable = new CodexBinary(cache, mirror, CLASSIFIER, recorded(archive(files("codex"), false, false), files("codex")),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        DriverAgent.CodexSettings settings = new DriverAgent.CodexSettings(Optional.empty(), Optional.empty(), Optional.of(state), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ToolServer.DEFAULT);

        try (Agent codex = DriverAgent.codex(settings, unreachable)) {
            AgentStatus status = codex.status();
            assertThat(status.readiness()).isEqualTo(AgentStatus.Readiness.CLI_MISSING);
            assertThat(status.detail()).contains(mirror.toString(), "executable(path)");
            assertThatThrownBy(() -> codex.startSession(SessionOptions.in(state).build()))
                    .isInstanceOf(AgentException.class)
                    .hasMessage(status.detail());
        }
    }

    @Test
    void aNewerReleaseIsToldApart()
    {
        assertThat(DriverAgent.newer("0.165.0", Optional.of("0.164.0"))).isTrue();
        assertThat(DriverAgent.newer("0.164.1-alpha.2", Optional.of("0.164.0"))).isTrue();
        assertThat(DriverAgent.newer("0.164.0", Optional.of("0.164.0"))).isFalse();
        assertThat(DriverAgent.newer("1.0", Optional.empty())).isFalse();
    }

    private CodexBinary binary(Map<String, String> recorded)
    {
        return binary(recorded, cache);
    }

    private CodexBinary binary(Map<String, String> recorded, Path folder)
    {
        return new CodexBinary(folder, URI.create(registry()), CLASSIFIER, recorded, Duration.ofSeconds(10), Duration.ofMinutes(1));
    }

    private String registry()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/npm/";
    }

    private URI address()
    {
        return URI.create(registry() + "@openai/codex/-/codex-" + Codex.VERSION + "-darwin-arm64.tgz");
    }

    private List<Path> versionFolders()
            throws IOException
    {
        try (Stream<Path> entries = Files.list(cache)) {
            return entries.filter(path -> path.getFileName().toString().matches("\\Q" + Codex.VERSION + "\\E-[0-9a-f]{12}")).toList();
        }
    }

    /** The four files Codex runs from, with contents that name {@code release}. */
    private static Map<String, String> files(String release)
    {
        Map<String, String> files = new LinkedHashMap<>();
        for (String file : CodexBinary.FILES) {
            files.put(file, file + " of " + release);
        }
        return files;
    }

    /** What this library would record for a package: its npm integrity and each of {@code files}' SHA-256. */
    private static Map<String, String> recorded(byte[] archive, Map<String, String> files)
    {
        ImmutableMap.Builder<String, String> recorded = ImmutableMap.builder();
        recorded.put(CLASSIFIER + "/archive", "sha512-" + Base64.getEncoder().encodeToString(Hashing.sha512().hashBytes(archive).asBytes()));
        files.forEach((file, text) -> recorded.put(CLASSIFIER + "/" + file, Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString()));
        return recorded.buildOrThrow();
    }

    /**
     * An npm package as npm packs it: a gzipped tar with everything under {@code package/}. {@code linked} stores {@code bin/codex} as
     * a symbolic link; {@code pax} names {@code codex-path/rg} through a pax header, as tar does for long names.
     */
    private static byte[] archive(Map<String, String> files, boolean linked, boolean pax)
            throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            entry(gzip, "package/package.json", "{\"name\":\"@openai/codex\"}".getBytes(StandardCharsets.UTF_8), '0');
            entry(gzip, ROOT + "codex-resources/voice.bin", new byte[700], '0');
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                if (linked && file.getKey().equals("bin/codex")) {
                    entry(gzip, ROOT + file.getKey(), new byte[0], '2');
                }
                else if (pax && file.getKey().equals("codex-path/rg")) {
                    entry(gzip, "PaxHeader/rg", paxPath(ROOT + file.getKey()), 'x');
                    entry(gzip, "rg-truncated-name", content, '0');
                }
                else {
                    entry(gzip, ROOT + file.getKey(), content, '0');
                }
            }
            gzip.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    private static byte[] paxPath(String path)
    {
        String record = " path=" + path + "\n";
        int length = record.length() + 2;
        while (String.valueOf(length).length() + record.length() != length) {
            length++;
        }
        return (length + record).getBytes(StandardCharsets.UTF_8);
    }

    private static void entry(OutputStream tar, String name, byte[] content, char type)
            throws IOException
    {
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000755");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", content.length));
        put(header, 136, "00000000000");
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        if (type == '2') {
            put(header, 157, "codex-elsewhere");
        }
        put(header, 257, "ustar");
        put(header, 263, "00");
        int sum = 0;
        for (byte value : header) {
            sum += value & 0xFF;
        }
        put(header, 148, String.format("%06o", sum));
        header[155] = (byte) ' ';
        tar.write(header);
        tar.write(content);
        tar.write(new byte[(512 - content.length % 512) % 512]);
    }

    private static void put(byte[] header, int offset, String text)
    {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static void send(HttpExchange exchange, byte[] body)
            throws IOException
    {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sleep(Duration duration)
    {
        try {
            Thread.sleep(duration);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private interface Responder
    {
        void respond(HttpExchange exchange)
                throws IOException;
    }
}
