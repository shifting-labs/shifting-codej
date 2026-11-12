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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.cli.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * The pinned Codex for this Mac: OpenAI's npm package for {@link Codex#VERSION}, downloaded once into a cache folder named by its
 * version and checksums. The download must match the SHA-512 this library records before it is opened; only the four files Codex
 * needs are taken from it, and each must match its recorded SHA-256 before Codex runs from there.
 */
final class CodexBinary
{
    static final String REGISTRY_PROPERTY = "codej.codex.registry";
    static final String REGISTRY_VARIABLE = "CODEJ_CODEX_REGISTRY";
    static final URI PUBLIC_REGISTRY = URI.create("https://registry.npmjs.org/");
    /** The launcher reads its layout from {@code codex-package.json}: the binaries in {@code bin}, ripgrep in {@code codex-path}. */
    static final List<String> FILES = ImmutableList.of("codex-package.json", "bin/codex", "bin/codex-code-mode-host", "codex-path/rg");
    private static final Log log = Log.of(CodexBinary.class);
    private static final Path CACHE = Path.of(System.getProperty("user.home"), "Library", "Caches", "shifting-codej", "codex");
    private static final String USED = ".last-used";
    private static final Duration UNUSED = Duration.ofDays(30);
    private static final Duration CONNECT = Duration.ofSeconds(30);

    private final Path cache;
    private final URI registry;
    private final String classifier;
    private final Map<String, String> recorded;
    private final Duration stall;
    private final Duration limit;

    CodexBinary(Path cache, URI registry, String classifier, Map<String, String> recorded, Duration stall, Duration limit)
    {
        this.cache = requireNonNull(cache, "cache is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.classifier = requireNonNull(classifier, "classifier is null");
        this.recorded = ImmutableMap.copyOf(recorded);
        this.stall = requireNonNull(stall, "stall is null");
        this.limit = requireNonNull(limit, "limit is null");
    }

    /** This Mac's Codex, from the registry the system property or environment names, else public npm. */
    static CodexBinary standard()
    {
        return new CodexBinary(CACHE, registry(Optional.ofNullable(System.getProperty(REGISTRY_PROPERTY)), Optional.ofNullable(System.getenv(REGISTRY_VARIABLE))),
                classifier(), recordedChecksums(), Duration.ofSeconds(60), Duration.ofMinutes(10));
    }

    /** A company mirror replaces public npm; the system property wins over the environment variable. */
    static URI registry(Optional<String> property, Optional<String> variable)
    {
        Optional<String> configured = property.or(() -> variable).map(String::strip).filter(value -> !value.isEmpty());
        if (configured.isEmpty()) {
            return PUBLIC_REGISTRY;
        }
        String value = configured.orElseThrow();
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("The Codex registry must be an http or https address: " + value);
        }
        return uri;
    }

    static String classifier()
    {
        return System.getProperty("os.arch").equals("aarch64") ? "osx-aarch_64" : "osx-x86_64";
    }

    /** Where this Mac's package is: npm's tarball path under the registry. */
    URI address()
    {
        return registry.resolve("@openai/codex/-/codex-" + Codex.VERSION + "-darwin-" + (arm() ? "arm64" : "x64") + ".tgz");
    }

    /**
     * Returns the Codex executable, downloading it first when the cache does not hold this release.
     *
     * @throws AgentException when this Mac has no recorded release, or the download fails or is not the recorded package
     */
    Path install()
    {
        if (!FILES.stream().allMatch(file -> recorded.containsKey(classifier + "/" + file)) || !recorded.containsKey(classifier + "/archive")) {
            throw new AgentException("This library records no Codex " + Codex.VERSION + " for " + classifier + "; set Codex.builder().executable(path)");
        }
        List<String> expected = FILES.stream().map(file -> recorded.get(classifier + "/" + file)).collect(ImmutableList.toImmutableList());
        String folder = Codex.VERSION + "-" + Hashing.sha256().hashString(String.join("\n", expected), StandardCharsets.UTF_8).toString().substring(0, 12);
        Path target = cache.resolve(folder);
        try {
            if (!matches(target, expected)) {
                Files.createDirectories(cache);
                Path work = Files.createTempDirectory(cache, folder + "-");
                try {
                    Path archive = work.resolve("package.tgz");
                    download(archive);
                    Path image = work.resolve("codex");
                    CodexPackage.extract(archive, image, expected, arm());
                    replace(image, target, expected);
                }
                finally {
                    MoreFiles.deleteRecursively(work, RecursiveDeleteOption.ALLOW_INSECURE);
                }
                if (!matches(target, expected)) {
                    throw new AgentException("The downloaded Codex " + Codex.VERSION + " in " + target + " does not match its checksums");
                }
            }
            markUsed(target);
        }
        catch (DownloadFailure failure) {
            throw new AgentException(failure.getMessage(), failure);
        }
        catch (IOException failure) {
            throw new AgentException("Codex " + Codex.VERSION + " could not be prepared in " + cache + ": " + failure.getMessage(), failure);
        }
        removeUnused(target);
        return target.resolve("bin/codex");
    }

    private void download(Path archive)
            throws IOException
    {
        URI address = address();
        log.info("Downloading Codex {} from {}", Codex.VERSION, address);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<InputStream> response;
            try {
                response = client.send(HttpRequest.newBuilder(address).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            catch (IOException failure) {
                throw unavailable(address, "no answer (" + failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()) + ")");
            }
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw unavailable(address, "not found");
                }
                if (response.statusCode() != 200) {
                    throw unavailable(address, "answered " + response.statusCode());
                }
                String integrity = "sha512-" + Base64.getEncoder().encodeToString(copy(body, archive, address).asBytes());
                if (!integrity.equals(recorded.get(classifier + "/archive"))) {
                    throw new AgentException("The file at " + address + " is not Codex " + Codex.VERSION + " as this library records it");
                }
            }
        }
        log.info("Downloaded Codex {}", Codex.VERSION);
    }

    /** Copies the body while hashing it, and stops it when no data came for the stall time or the whole took too long. */
    private HashCode copy(InputStream body, Path archive, URI address)
            throws IOException
    {
        Thread reader = Thread.currentThread();
        long started = System.nanoTime();
        AtomicLong progress = new AtomicLong(started);
        AtomicReference<String> stopped = new AtomicReference<>();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            String reason = now - progress.get() > stall.toNanos() ? "no data for " + stall.toSeconds() + " seconds"
                    : now - started > limit.toNanos() ? "not finished within " + limit.toMinutes() + " minutes" : null;
            if (reason != null && stopped.compareAndSet(null, reason)) {
                // A read blocked on the next packet wakes only when interrupted; closing alone may leave it waiting.
                reader.interrupt();
                try {
                    body.close();
                }
                catch (IOException ignored) {
                    // The reader fails on the interrupt either way.
                }
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
        try (OutputStream file = Files.newOutputStream(archive); HashingOutputStream hashing = new HashingOutputStream(Hashing.sha512(), file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                hashing.write(buffer, 0, read);
                progress.set(System.nanoTime());
            }
            if (stopped.get() != null) {
                throw unavailable(address, stopped.get());
            }
            return hashing.hash();
        }
        catch (IOException failure) {
            throw failure instanceof DownloadFailure ? failure
                    : unavailable(address, Optional.ofNullable(stopped.get()).orElse("the download broke off (" + failure.getClass().getSimpleName() + ")"));
        }
        finally {
            watchdog.shutdownNow();
            if (stopped.get() != null) {
                // The interrupt was ours; the caller's thread must not keep it.
                Thread.interrupted();
            }
        }
    }

    /** Each build marks its release as used, so the sweep keeps it while any program still runs it. */
    private static void markUsed(Path target)
            throws IOException
    {
        Path used = target.resolve(USED);
        if (Files.exists(used)) {
            Files.setLastModifiedTime(used, FileTime.from(Instant.now()));
        }
        else {
            Files.createFile(used);
        }
    }

    /** Deletes the releases, and leftover downloads, that no build has used for thirty days. */
    private void removeUnused(Path target)
    {
        Instant before = Instant.now().minus(UNUSED);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cache, Files::isDirectory)) {
            for (Path entry : entries) {
                Path used = entry.resolve(USED);
                Instant last = Files.getLastModifiedTime(Files.exists(used) ? used : entry).toInstant();
                if (!entry.equals(target) && last.isBefore(before)) {
                    MoreFiles.deleteRecursively(entry, RecursiveDeleteOption.ALLOW_INSECURE);
                }
            }
        }
        catch (IOException | DirectoryIteratorException failure) {
            log.debug("Cannot remove unused Codex releases from {}", cache, failure);
        }
    }

    /**
     * Another program may download the same release at once. A good folder that landed first is kept, since that program may already
     * run Codex from it; only a damaged one is replaced.
     */
    private static void replace(Path image, Path target, List<String> expected)
            throws IOException
    {
        if (Files.exists(target)) {
            if (matches(target, expected)) {
                return;
            }
            MoreFiles.deleteRecursively(target, RecursiveDeleteOption.ALLOW_INSECURE);
        }
        try {
            Files.move(image, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (FileSystemException collided) {
            // A rename onto the other program's folder fails, as "directory not empty" on macOS; that folder stays, and ours is
            // deleted with the download.
            if (!matches(target, expected)) {
                throw collided;
            }
        }
    }

    private static boolean matches(Path folder, List<String> expected)
            throws IOException
    {
        for (int index = 0; index < FILES.size(); index++) {
            Path file = folder.resolve(FILES.get(index));
            if (!Files.isRegularFile(file) || !MoreFiles.asByteSource(file).hash(Hashing.sha256()).toString().equals(expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean arm()
    {
        return classifier.equals("osx-aarch_64");
    }

    private static DownloadFailure unavailable(URI address, String reason)
    {
        return new DownloadFailure("Codex " + Codex.VERSION + " could not be downloaded from " + address + ": " + reason
                + ". Check the network or the " + REGISTRY_VARIABLE + " mirror, or set Codex.builder().executable(path)");
    }

    private static Map<String, String> recordedChecksums()
    {
        Properties properties = new Properties();
        try (InputStream recorded = requireNonNull(CodexBinary.class.getResourceAsStream("codex-checksums.properties"), "checksums are missing")) {
            properties.load(recorded);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return properties.stringPropertyNames().stream().collect(ImmutableMap.toImmutableMap(name -> name, properties::getProperty));
    }

    /** A download failure whose message is already the one to show. */
    private static final class DownloadFailure
            extends IOException
    {
        DownloadFailure(String message)
        {
            super(message);
        }
    }
}
