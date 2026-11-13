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

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.CodingTools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.Edit;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileContent;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileEntry;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileKind;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileListing;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ListFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchAction;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ReadFile;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchMatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchResult;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Descriptor-relative, no-follow file operations. One lease serializes SDK coding sessions per physical workspace. */
final class WorkspaceFiles
        implements AutoCloseable
{
    private static final Set<String> PROTECTED = Set.of(
            ".git", ".hg", ".svn", ".ssh", ".aws", ".config", ".codex", ".claude", ".gemini", ".pi", ".opencode", ".agents", ".netrc", ".git-credentials");
    private static final String PROTECTED_PATTERN = "/(" + String.join("|", PROTECTED.stream().sorted().map(name -> name.replace(".", "\\.")).toList())
            + "|\\.codej[^/]*)(/|$)";
    private final Path root;
    private final CodingTools limits;
    private final boolean writable;
    private final List<Path> excluded;
    private final SecureDirectoryStream<Path> directory;
    private final Object rootKey;
    private final FileChannel lockChannel;
    private final FileLock lock;

    WorkspaceFiles(Path worktree, CodingTools limits, boolean writable, List<Path> excluded)
            throws IOException
    {
        this.root = worktree.toRealPath();
        this.limits = limits;
        this.writable = writable;
        List<Path> physicalExclusions = new ArrayList<>();
        for (Path path : List.copyOf(excluded)) {
            physicalExclusions.add(physicalPath(path));
        }
        this.excluded = List.copyOf(physicalExclusions);
        if (root.getParent() == null || List.of("/System", "/usr", "/bin", "/dev").stream().map(Path::of).anyMatch(path -> path.startsWith(root))) {
            throw new CodingToolException("PATH_DENIED", "A dedicated workspace directory is required");
        }
        rootKey = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
        if (rootKey == null) {
            throw new CodingToolException("UNSUPPORTED_FILESYSTEM", "Stable filesystem identities are required");
        }
        Path locks = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("codej-workspace-locks");
        try {
            Files.createDirectory(locks, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Persistent lock names must survive release, otherwise another process can lock a different inode.
        }
        if (Files.isSymbolicLink(locks) || !Files.isDirectory(locks, NOFOLLOW_LINKS)
                || !Files.getOwner(locks, NOFOLLOW_LINKS).equals(Files.getOwner(locks.getParent()))
                || !Files.getPosixFilePermissions(locks, NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new CodingToolException("PATH_DENIED", "Workspace lock directory is not private");
        }
        Path lockPath = locks.resolve(Hashing.sha256().hashString(rootKey.toString(), StandardCharsets.UTF_8) + ".lock");
        lockChannel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        FileLock acquired = null;
        SecureDirectoryStream<Path> opened = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) {
                throw new CodingToolException("WORKSPACE_BUSY", "Another coding session owns this workspace");
            }
            DirectoryStream<Path> stream = Files.newDirectoryStream(root);
            if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
                stream.close();
                throw new CodingToolException("UNSUPPORTED_FILESYSTEM", "Secure directory operations are required");
            }
            opened = secure;
            if (!rootKey.equals(secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey())) {
                throw new CodingToolException("WORKSPACE_CHANGED", "Workspace identity changed while being opened");
            }
            lock = acquired;
            directory = secure;
        }
        catch (IOException | RuntimeException failure) {
            if (opened != null) {
                opened.close();
            }
            if (acquired != null) {
                acquired.release();
            }
            lockChannel.close();
            throw failure;
        }
    }

    private static Path physicalPath(Path path)
            throws IOException
    {
        Path existing = path.toAbsolutePath().normalize();
        ArrayDeque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(existing, NOFOLLOW_LINKS)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        Path resolved = existing.toRealPath();
        for (Path component : missing) {
            resolved = resolved.resolve(component);
        }
        return resolved;
    }

    static String protectedPattern(Path root)
    {
        StringBuilder literal = new StringBuilder("^");
        for (char character : root.toString().toCharArray()) {
            if ("\\.^$|?*+()[]{}\"".indexOf(character) >= 0) {
                literal.append('\\');
            }
            literal.append(character);
        }
        return literal + "(/[^/]+)*" + PROTECTED_PATTERN;
    }

    Path root() { return root; }

    void verifyRoot()
            throws IOException
    {
        if (!rootKey.equals(Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey())) {
            throw new CodingToolException("WORKSPACE_CHANGED", "Workspace identity changed");
        }
    }

    Path commandDirectory(String relative)
            throws IOException
    {
        verifyRoot();
        try (SecureDirectoryStream<Path> ignored = openDirectory(Path.of(relative))) {
            return root.resolve(relative).normalize();
        }
    }

    private boolean denied(Path relative)
    {
        for (Path part : relative) {
            String name = part.toString();
            if (PROTECTED.contains(name) || name.startsWith(".codej")) {
                return true;
            }
        }
        Path absolute = root.resolve(relative).normalize();
        return excluded.stream().anyMatch(absolute::startsWith);
    }

    private void permitted(Path relative)
    {
        if (denied(relative)) {
            throw new CodingToolException("PATH_DENIED", "The requested path is protected");
        }
    }

    private SecureDirectoryStream<Path> openDirectory(Path relative)
            throws IOException
    {
        permitted(relative);
        SecureDirectoryStream<Path> current = directory.newDirectoryStream(Path.of("."), NOFOLLOW_LINKS);
        try {
            for (Path part : relative.normalize()) {
                if (part.toString().isEmpty() || part.toString().equals(".")) {
                    continue;
                }
                SecureDirectoryStream<Path> next = current.newDirectoryStream(part, NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        }
        catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    private record FileIdentity(Object key, long links, long size, FileTime modified)
    {
        FileIdentity
        {
            if (key == null || links != 1 || size < 0) {
                throw new CodingToolException("PATH_DENIED", "Only ordinary files with one link are accessible");
            }
        }
    }

    private FileIdentity identity(SecureDirectoryStream<Path> parent, Path relative)
            throws IOException
    {
        BasicFileAttributes attrs = parent.getFileAttributeView(relative.getFileName(), BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
        if (!attrs.isRegularFile()) {
            throw new CodingToolException("PATH_DENIED", "Only regular files are accessible");
        }
        Map<String, Object> unix = Files.readAttributes(root.resolve(relative), "unix:fileKey,nlink", NOFOLLOW_LINKS);
        Object count = unix.get("nlink");
        if (!(count instanceof Number number)) {
            throw new CodingToolException("UNSUPPORTED_FILESYSTEM", "File link counts are required");
        }
        Object pathKey = unix.get("fileKey");
        if (!java.util.Objects.equals(pathKey, attrs.fileKey())) {
            throw new CodingToolException("WORKSPACE_CHANGED", "File identity changed");
        }
        return new FileIdentity(attrs.fileKey(), number.longValue(), attrs.size(), attrs.lastModifiedTime());
    }

    FileContent read(ReadFile request)
            throws IOException
    {
        verifyRoot();
        Path relative = Path.of(request.path());
        permitted(relative);
        try (SecureDirectoryStream<Path> parent = openDirectory(parent(relative))) {
            byte[] bytes = bytes(parent, relative);
            String value = text(bytes);
            int firstLine = request.startLine().orElse(1);
            int offset = 0;
            for (int line = 1; line < firstLine && offset < value.length(); line++) {
                int newline = value.indexOf('\n', offset);
                offset = newline < 0 ? value.length() : newline + 1;
            }
            int end = (int) Math.min(value.length(), (long) offset + request.maximumCharacters().orElse(4096));
            if (end > offset && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
            }
            return new FileContent(request.path(), digest(bytes), value.substring(offset, end), firstLine, end < value.length());
        }
    }

    private byte[] bytes(SecureDirectoryStream<Path> parent, Path relative)
            throws IOException
    {
        interrupted();
        FileIdentity before = identity(parent, relative);
        if (before.size() > limits.maximumFileBytes()) {
            throw new CodingToolException("FILE_LIMIT", "File exceeds its byte limit");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(before.size() + 1, limits.maximumFileBytes() + 1L));
        try (SeekableByteChannel channel = parent.newByteChannel(relative.getFileName(), Set.of(StandardOpenOption.READ, NOFOLLOW_LINKS))) {
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                interrupted();
            }
        }
        if (buffer.position() > limits.maximumFileBytes()) {
            throw new CodingToolException("FILE_LIMIT", "File exceeds its byte limit");
        }
        if (!before.equals(identity(parent, relative))) {
            throw new CodingToolException("WORKSPACE_CHANGED", "File changed while being read");
        }
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    FileListing list(ListFiles request)
            throws IOException
    {
        verifyRoot();
        List<FileEntry> entries = new ArrayList<>();
        Path base = Path.of(request.directory());
        int visited = 0;
        try (SecureDirectoryStream<Path> stream = openDirectory(base)) {
            for (Path entry : stream) {
                interrupted();
                if (++visited > limits.maximumEntries()) {
                    return new FileListing(entries, true);
                }
                Path relative = base.resolve(entry.getFileName()).normalize();
                if (denied(relative)) {
                    continue;
                }
                if (entries.size() == request.limit()) {
                    return new FileListing(entries, true);
                }
                BasicFileAttributes attrs = stream.getFileAttributeView(entry.getFileName(), BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
                entries.add(new FileEntry(relative.toString(), attrs.isDirectory() ? FileKind.DIRECTORY : attrs.isRegularFile() ? FileKind.FILE : FileKind.BLOCKED));
            }
        }
        return new FileListing(entries, false);
    }

    private interface Visitor
    {
        boolean visit(Path relative, SecureDirectoryStream<Path> parent, BasicFileAttributes attrs)
                throws IOException;
    }

    private boolean walk(Path relative, int[] visited, Visitor visitor)
            throws IOException
    {
        if (relative.getNameCount() > 64) {
            throw new CodingToolException("ENTRY_LIMIT", "Directory nesting exceeds its limit");
        }
        try (SecureDirectoryStream<Path> stream = openDirectory(relative)) {
            for (Path entry : stream) {
                interrupted();
                if (++visited[0] > limits.maximumEntries()) {
                    return false;
                }
                Path child = relative.resolve(entry.getFileName()).normalize();
                BasicFileAttributes attrs = stream.getFileAttributeView(entry.getFileName(), BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
                if (!visitor.visit(child, stream, attrs)) {
                    return false;
                }
                if (!denied(child) && attrs.isDirectory() && !walk(child, visited, visitor)) {
                    return false;
                }
            }
        }
        return true;
    }

    List<Path> sandboxDenials()
            throws IOException
    {
        verifyRoot();
        List<Path> result = new ArrayList<>(excluded);
        if (!walk(Path.of("."), new int[1], (relative, parent, attrs) -> {
            if (denied(relative) || attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
                result.add(root.resolve(relative));
            }
            else if (attrs.isRegularFile()) {
                try {
                    identity(parent, relative);
                }
                catch (CodingToolException failure) {
                    result.add(root.resolve(relative));
                }
            }
            return result.size() <= 4096;
        })) {
            throw new CodingToolException("ENTRY_LIMIT", "Workspace is too large to establish command containment");
        }
        return List.copyOf(result);
    }

    SearchResult search(SearchFiles request)
            throws IOException
    {
        verifyRoot();
        List<SearchMatch> matches = new ArrayList<>();
        long[] examined = new long[1];
        String needle = request.caseSensitive() ? request.text() : request.text().toLowerCase(Locale.ROOT);
        boolean complete = walk(Path.of(request.directory()), new int[1], (relative, parent, attrs) -> {
            if (denied(relative) || !attrs.isRegularFile() || attrs.size() > limits.maximumFileBytes()) {
                return true;
            }
            if (examined[0] + attrs.size() > limits.maximumSearchBytes()) {
                return false;
            }
            byte[] content;
            try {
                content = bytes(parent, relative);
            }
            catch (CodingToolException failure) {
                if (failure.failure().code().equals("PATH_DENIED")) {
                    return true;
                }
                throw failure;
            }
            examined[0] += content.length;
            String value;
            try {
                value = text(content);
            }
            catch (CodingToolException failure) {
                return true;
            }
            int start = 0;
            int line = 1;
            while (start < value.length()) {
                interrupted();
                int end = value.indexOf('\n', start);
                if (end < 0) {
                    end = value.length();
                }
                String row = value.substring(start, end);
                String haystack = request.caseSensitive() ? row : row.toLowerCase(Locale.ROOT);
                int match = haystack.indexOf(needle);
                if (match >= 0) {
                    if (matches.size() == request.limit()) {
                        return false;
                    }
                    int preview = Math.max(0, Math.min(match, row.length()) - 128);
                    if (preview > 0 && Character.isLowSurrogate(row.charAt(preview))) {
                        preview++;
                    }
                    int previewEnd = Math.min(row.length(), preview + 512);
                    if (previewEnd > preview && previewEnd < row.length() && Character.isHighSurrogate(row.charAt(previewEnd - 1))) {
                        previewEnd--;
                    }
                    matches.add(new SearchMatch(relative.toString(), line, row.substring(preview, previewEnd)));
                }
                start = end + 1;
                line++;
            }
            return true;
        });
        return new SearchResult(matches, !complete, examined[0]);
    }

    PatchResult patch(ApplyPatch request)
            throws IOException
    {
        if (!writable) {
            throw new CodingToolException("READ_ONLY", "This session cannot change files");
        }
        verifyRoot();
        Path relative = Path.of(request.path());
        permitted(relative);
        try (SecureDirectoryStream<Path> parent = openDirectory(parent(relative))) {
            if (request.action() == PatchAction.CREATE) {
                byte[] after = encoded(request.edits().getFirst().after());
                write(parent, relative.getFileName(), after, PosixFilePermissions.fromString("rw-------"));
                sync(parent, relative);
                return new PatchResult(request.path(), request.action(), Optional.of(digest(after)));
            }
            byte[] before = bytes(parent, relative);
            String preimage = digest(before);
            if (!preimage.equals(request.expectedSha256().orElseThrow())) {
                throw new CodingToolException("STALE_PREIMAGE", "File no longer matches expectedSha256");
            }
            if (request.action() == PatchAction.DELETE) {
                parent.deleteFile(relative.getFileName());
                sync(parent, relative);
                return new PatchResult(request.path(), request.action(), Optional.empty());
            }
            String value = text(before);
            for (Edit edit : request.edits()) {
                int index = value.indexOf(edit.before());
                if (index < 0 || value.indexOf(edit.before(), index + 1) >= 0) {
                    throw new CodingToolException("AMBIGUOUS_EDIT", "Every replacement must match exactly once");
                }
                long length = (long) value.length() - edit.before().length() + edit.after().length();
                if (length > limits.maximumFileBytes()) {
                    throw new CodingToolException("FILE_LIMIT", "Patch exceeds its file limit");
                }
                value = value.substring(0, index) + edit.after() + value.substring(index + edit.before().length());
            }
            byte[] after = encoded(value);
            Path temporary = Path.of(".codej-patch-" + UUID.randomUUID());
            try {
                var permissions = parent.getFileAttributeView(relative.getFileName(), PosixFileAttributeView.class, NOFOLLOW_LINKS).readAttributes().permissions();
                write(parent, temporary, after, permissions);
                if (!digest(bytes(parent, relative)).equals(preimage)) {
                    throw new CodingToolException("STALE_PREIMAGE", "File changed before replacement");
                }
                parent.move(temporary, parent, relative.getFileName());
            }
            finally {
                try {
                    parent.deleteFile(temporary);
                }
                catch (NoSuchFileException ignored) {
                    // Successful atomic replacement consumed the temporary file.
                }
            }
            sync(parent, relative);
            return new PatchResult(request.path(), request.action(), Optional.of(digest(after)));
        }
    }

    private void sync(SecureDirectoryStream<Path> directory, Path relative)
    {
        Path path = root.resolve(parent(relative));
        try {
            Object anchored = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            if (!java.util.Objects.equals(anchored, Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey())) {
                throw new IOException("parent identity changed");
            }
            try (FileChannel parent = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                parent.force(true);
            }
        }
        catch (IOException failure) {
            throw new CodingToolException("PATCH_UNCERTAIN", "File change durability could not be confirmed", true);
        }
    }

    private void write(SecureDirectoryStream<Path> parent, Path name, byte[] bytes, Set<java.nio.file.attribute.PosixFilePermission> permissions)
            throws IOException
    {
        interrupted();
        boolean created = false;
        try (SeekableByteChannel channel = parent.newByteChannel(name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            created = true;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            parent.getFileAttributeView(name, PosixFileAttributeView.class, NOFOLLOW_LINKS).setPermissions(permissions);
            if (channel instanceof FileChannel file) {
                file.force(true);
            }
        }
        catch (IOException failure) {
            if (created) {
                try {
                    parent.deleteFile(name);
                }
                catch (IOException cleanupFailure) {
                    throw new CodingToolException("PATCH_UNCERTAIN", "Incomplete file creation could not be rolled back", true);
                }
            }
            throw failure;
        }
    }

    private byte[] encoded(String value)
    {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        }
        catch (CharacterCodingException failure) {
            throw new CodingToolException("INVALID_TEXT", "Patch text must be valid Unicode");
        }
        if (bytes.length > limits.maximumFileBytes()) {
            throw new CodingToolException("FILE_LIMIT", "Patch exceeds its file limit");
        }
        return bytes;
    }

    private static String text(byte[] bytes)
    {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) {
                throw new CodingToolException("BINARY_FILE", "Only UTF-8 text files are supported");
            }
            return value;
        }
        catch (CharacterCodingException failure) {
            throw new CodingToolException("BINARY_FILE", "Only UTF-8 text files are supported");
        }
    }

    private static String digest(byte[] value) { return Hashing.sha256().hashBytes(value).toString(); }

    private static Path parent(Path relative) { return relative.getParent() == null ? Path.of(".") : relative.getParent(); }

    private static void interrupted()
    {
        if (Thread.currentThread().isInterrupted()) {
            throw new CodingToolException("INTERRUPTED", "File operation interrupted");
        }
    }

    @Override
    public void close()
            throws IOException
    {
        directory.close();
        lock.release();
        lockChannel.close();
    }
}
