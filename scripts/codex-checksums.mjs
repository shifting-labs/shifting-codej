#!/usr/bin/env node
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

// Prints what the agent SDK records for one macOS architecture's pinned Codex: the npm package's integrity, which codej
// checks a download against, then the SHA-256 of each file codej takes from it.
//
//   node scripts/codex-checksums.mjs --arch arm64|x64

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { ARCHITECTURES, FILES, checkLayout, download, sha256 } from "./codex-release.mjs";

const { values } = parseArgs({ options: { arch: { type: "string" } } });
const architecture = ARCHITECTURES[values.arch];
if (!architecture) {
  console.error("Usage: node scripts/codex-checksums.mjs --arch arm64|x64");
  process.exit(2);
}

const work = mkdtempSync(join(tmpdir(), "codex-checksums-"));
try {
  const { vendor, integrity } = await download(values.arch, work);
  checkLayout(vendor);
  console.log(`${architecture.classifier}/archive=${integrity}`);
  for (const file of FILES) {
    console.log(`${architecture.classifier}/${file}=${sha256(join(vendor, file))}`);
  }
}
finally {
  rmSync(work, { recursive: true, force: true });
}
