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
package io.github.shiftinglabs.codej.cli.spi.prompt;

/**
 * Explicitly configured factory, safe to share between sessions. ID lookup is pure. Each successful
 * open returns a fresh independently owned contributor; failed open cleans its own partial resources.
 * Open performs no inference or model-requested effects. Configuration must be immutable.
 *
 * @param <C> provider configuration type; no runtime Class token is required
 */
public interface PromptProvider<C>
{
    String id();

    PromptContributor open(C configuration, PromptSessionContext context);
}
