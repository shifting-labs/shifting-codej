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

import io.github.shiftinglabs.codej.CodingTools;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class TestWorkspaceResources
{
    @TempDir
    Path directory;

    private AbstractTestWorkspaceReader.Fixture fixture(boolean writable)
            throws Exception
    {
        Path root = Files.createTempDirectory(directory, "workspace-");
        WorkspaceFiles files = new WorkspaceFiles(root, CodingTools.defaults(), writable, List.of());
        return new AbstractTestWorkspaceReader.Fixture()
        {
            @Override
            public WorkspaceReader reader() { return files; }

            @Override
            public WorkspaceEditor editor() { return files; }

            @Override
            public void seed(String path, String text)
                    throws Exception
            {
                Files.writeString(root.resolve(path), text);
            }

            @Override
            public Optional<String> content(String path)
                    throws Exception
            {
                return Files.exists(root.resolve(path)) ? Optional.of(Files.readString(root.resolve(path))) : Optional.empty();
            }

            @Override
            public void close()
                    throws Exception
            {
                files.close();
            }
        };
    }

    @Nested
    class Reader
            extends AbstractTestWorkspaceReader
    {
        @Override
        protected Fixture open(boolean writable)
                throws Exception
        {
            return fixture(writable);
        }
    }

    @Nested
    class Editor
            extends AbstractTestWorkspaceEditor
    {
        @Override
        protected AbstractTestWorkspaceReader.Fixture open(boolean writable)
                throws Exception
        {
            return fixture(writable);
        }
    }
}
