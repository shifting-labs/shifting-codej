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

/** Safe, structured failures. Uncertain effects must retain the engine's started receipt and ownership. */
public final class CodingToolException
        extends RuntimeException
{
    private final ToolMessages.Failure failure;
    private final boolean uncertain;

    CodingToolException(String code, String message) { this(code, message, false); }

    CodingToolException(String code, String message, boolean uncertain)
    {
        super(message);
        failure = new ToolMessages.Failure(code, message);
        this.uncertain = uncertain;
    }

    public ToolMessages.Failure failure() { return failure; }

    public boolean uncertain() { return uncertain; }
}
