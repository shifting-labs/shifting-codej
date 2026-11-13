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

/** Validates a schema dialect and returns its deterministic fingerprint representation. */
@FunctionalInterface
public interface ToolSchemaFormat
{
    /**
     * Validates the complete source schema, including supported assertions. Implementations must
     * be side-effect-free and preserve their dialect's compatibility and size rules.
     *
     * @param schema original schema JSON
     * @return non-null canonical JSON used for equality and fingerprints
     * @throws IllegalArgumentException for malformed or unsupported schemas
     */
    String canonicalize(String schema);
}
