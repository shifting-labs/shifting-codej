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
package io.github.shiftinglabs.codej.tool;

/**
 * Transport input consumed only by parameter codecs. Implementations preserve their transport's
 * validation and limits; the default factory accepts a strict JSON object of at most 64 KiB.
 * Alternate bindings can preserve existing host limits without changing the tool SPI.
 */
public interface ToolArguments
{
    /**
     * Returns the complete JSON argument object. Do not log it; it may contain credentials.
     *
     * @return JSON input
     */
    String json();

    /**
     * Creates a bounded JSON envelope without invoking a parameter binder.
     *
     * @param json a single JSON object of at most 64 KiB in UTF-8
     * @return immutable transport arguments whose string representation hides the data
     * @throws IllegalArgumentException for malformed, duplicate, trailing or excessive input
     */
    static ToolArguments of(String json) { return new JsonToolArguments(json); }
}
