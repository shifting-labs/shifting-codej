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
package io.github.shiftinglabs.codej.cli.spi.extension;

/** Restrictive admission hook; CONTINUE never grants authority or bypasses approval/containment. */
@FunctionalInterface
public interface ToolPolicy
{
    /** Pure, bounded decision over prepared metadata. Null/throwing decisions fail closed. */
    Decision evaluate(ToolPolicyContext context);

    enum Decision
    {
        CONTINUE,
        DENY
    }
}
