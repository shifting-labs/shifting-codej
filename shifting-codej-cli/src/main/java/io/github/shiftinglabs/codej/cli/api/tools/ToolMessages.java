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

import io.github.shiftinglabs.codej.cli.process.ProcessExit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Typed wire contracts; constructors reject invalid input before any filesystem or process effects. */
public final class ToolMessages
{
    private ToolMessages() {}

    public record ReadFile(String path, Optional<Integer> startLine, Optional<Integer> maximumCharacters)
    {
        public ReadFile(String path) { this(path, Optional.empty(), Optional.empty()); }

        public ReadFile
        {
            path = relative(path, false);
            requireNonNull(startLine, "startLine is null").ifPresent(value -> checkArgument(value > 0, "startLine must be positive"));
            requireNonNull(maximumCharacters, "maximumCharacters is null").ifPresent(value ->
                    checkArgument(value > 0 && value <= 16384, "maximumCharacters must be between 1 and 16384"));
        }
    }

    public record ListFiles(String directory, int limit)
    {
        public ListFiles
        {
            directory = relative(directory, true);
            checkArgument(limit > 0 && limit <= 1000, "limit must be between 1 and 1000");
        }
    }

    public record SearchFiles(String directory, String text, boolean caseSensitive, int limit)
    {
        public SearchFiles
        {
            directory = relative(directory, true);
            requireNonNull(text, "text is null");
            checkArgument(!text.isEmpty() && text.length() <= 1024 && text.indexOf('\0') < 0 && text.indexOf('\n') < 0
                    && text.indexOf('\r') < 0, "search text must be a nonempty single line of at most 1024 characters");
            checkArgument(limit > 0 && limit <= 200, "limit must be between 1 and 200");
        }
    }

    public enum PatchAction { CREATE, UPDATE, DELETE }

    public record Edit(String before, String after)
    {
        public Edit
        {
            requireNonNull(before, "before is null");
            requireNonNull(after, "after is null");
            checkArgument(before.indexOf('\0') < 0 && after.indexOf('\0') < 0, "binary edits are unsupported");
            checkArgument(before.length() <= 16 * 1024 * 1024 && after.length() <= 16 * 1024 * 1024, "edit exceeds its limit");
            checkArgument(!before.equals(after), "edit changes nothing");
            checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(before) && StandardCharsets.UTF_8.newEncoder().canEncode(after),
                    "edit text must be valid Unicode");
        }
    }

    public record ApplyPatch(String path, PatchAction action, Optional<String> expectedSha256, List<Edit> edits)
    {
        public ApplyPatch
        {
            path = relative(path, false);
            requireNonNull(action, "action is null");
            requireNonNull(expectedSha256, "expectedSha256 is null").ifPresent(value ->
                    checkArgument(value.matches("[a-f0-9]{64}"), "expectedSha256 must be a lowercase SHA-256 digest"));
            edits = List.copyOf(edits);
            checkArgument(edits.size() <= 100, "too many edits");
            switch (action) {
                case CREATE -> checkArgument(expectedSha256.isEmpty() && edits.size() == 1 && edits.getFirst().before().isEmpty(),
                        "create requires one insertion and no preimage");
                case UPDATE -> checkArgument(expectedSha256.isPresent() && !edits.isEmpty() && edits.stream().noneMatch(edit -> edit.before().isEmpty()),
                        "update requires a preimage and nonempty replacements");
                case DELETE -> checkArgument(expectedSha256.isPresent() && edits.isEmpty(), "delete requires a preimage and no edits");
            }
        }
    }

    public record ShellCommand(List<String> argv, String directory, int timeoutSeconds)
    {
        public ShellCommand
        {
            argv = List.copyOf(argv);
            checkArgument(!argv.isEmpty() && argv.size() <= 128, "argv must have 1 to 128 arguments");
            checkArgument(argv.stream().allMatch(value -> !value.isBlank() && value.indexOf('\0') < 0), "invalid argv");
            checkArgument(argv.stream().mapToLong(String::length).sum() <= 32768, "argv is too large");
            checkArgument(Path.of(argv.getFirst()).isAbsolute(), "executable must be an absolute path");
            directory = relative(directory, true);
            checkArgument(timeoutSeconds > 0 && timeoutSeconds <= 300, "invalid command timeout");
        }
    }

    public record FileContent(String path, String sha256, String text, int firstLine, boolean truncated)
    {
        public FileContent
        {
            path = relative(path, false);
            checkArgument(requireNonNull(sha256, "sha256 is null").matches("[a-f0-9]{64}"), "invalid digest");
            requireNonNull(text, "text is null");
            checkArgument(firstLine > 0, "firstLine must be positive");
        }
    }

    public enum FileKind { DIRECTORY, FILE, BLOCKED }

    public record FileEntry(String path, FileKind kind)
    {
        public FileEntry
        {
            path = relative(path, false);
            requireNonNull(kind, "kind is null");
        }
    }

    public record FileListing(List<FileEntry> entries, boolean truncated)
    {
        public FileListing { entries = List.copyOf(entries); }
    }

    public record SearchMatch(String path, int line, String text)
    {
        public SearchMatch
        {
            path = relative(path, false);
            checkArgument(line > 0, "line must be positive");
            checkArgument(requireNonNull(text, "text is null").length() <= 512, "preview is too large");
        }
    }

    public record SearchResult(List<SearchMatch> matches, boolean truncated, long examinedBytes)
    {
        public SearchResult
        {
            matches = List.copyOf(matches);
            checkArgument(examinedBytes >= 0, "examinedBytes must be nonnegative");
        }
    }

    public record PatchResult(String path, PatchAction action, Optional<String> sha256)
    {
        public PatchResult
        {
            path = relative(path, false);
            requireNonNull(action, "action is null");
            requireNonNull(sha256, "sha256 is null").ifPresent(value -> checkArgument(value.matches("[a-f0-9]{64}"), "invalid digest"));
            checkArgument((action == PatchAction.DELETE) == sha256.isEmpty(), "patch action and result disagree");
        }
    }

    public record CommandResult(int exitCode, ProcessExit.Reason reason, String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)
    {
        public CommandResult
        {
            requireNonNull(reason, "reason is null");
            requireNonNull(stdout, "stdout is null");
            requireNonNull(stderr, "stderr is null");
        }
    }

    public record Failure(String code, String message)
    {
        public Failure
        {
            checkArgument(requireNonNull(code, "code is null").matches("[A-Z_]{1,64}"), "invalid failure code");
            checkArgument(!requireNonNull(message, "message is null").isBlank() && message.length() <= 512, "invalid failure message");
        }
    }

    static String relative(String value, boolean rootAllowed)
    {
        requireNonNull(value, "path is null");
        checkArgument(!value.isBlank() && value.length() <= 4096 && value.indexOf('\0') < 0, "invalid relative path");
        Path path = Path.of(value);
        checkArgument(!path.isAbsolute(), "absolute paths are forbidden");
        for (Path component : path) {
            checkArgument(!component.toString().equals(".."), "parent traversal is forbidden");
        }
        String normalized = path.normalize().toString();
        checkArgument(rootAllowed || !normalized.isEmpty(), "a file path is required");
        return normalized.isEmpty() ? "." : normalized;
    }
}
