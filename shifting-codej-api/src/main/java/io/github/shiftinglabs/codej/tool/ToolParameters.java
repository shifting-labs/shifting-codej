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
 * Describes and binds one immutable parameter contract. Implementations must be thread-safe and
 * enforce the advertised schema, including record invariants, without executing tool effects.
 * No reflection or Java runtime class token is required by this interface.
 *
 * @param <P> validated parameters, which may be a record or an interface implemented by immutable values
 */
public interface ToolParameters<P>
{
    /**
     * Returns a stable, side-effect-free schema for this binding.
     *
     * @return the parameter schema
     */
    ToolSchema schema();

    /**
     * Validates arguments and returns an immutable snapshot. Reject missing/extra fields, wrong
     * types and invalid domain values before any approval or effects; never include input secrets in errors.
     *
     * @param arguments bounded transport arguments
     * @return validated, non-null parameters
     * @throws IllegalArgumentException for invalid arguments
     */
    P decode(ToolArguments arguments);
}
