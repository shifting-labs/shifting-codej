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

// The pinned Codex release, as the agent SDK records it, and OpenAI's npm package that carries it.

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const SDK = join(root, "shifting-codej", "src", "main");

export const ARCHITECTURES = {
  arm64: { triple: "aarch64-apple-darwin", classifier: "osx-aarch_64" },
  x64: { triple: "x86_64-apple-darwin", classifier: "osx-x86_64" },
};

// The launcher, its code-mode host and the ripgrep it searches with. The voice resources stay out.
export const FILES = ["codex-package.json", "bin/codex", "bin/codex-code-mode-host", "codex-path/rg"];

/** Codex.VERSION, the one release the SDK runs. */
export function version() {
  const source = readFileSync(join(SDK, "java", "io", "github", "shiftinglabs", "codej", "Codex.java"), "utf8");
  const match = source.match(/public static final String VERSION = "([^"]+)";/);
  if (!match) {
    throw new Error("Codex.java names no VERSION");
  }
  return match[1];
}

/** The SHA-256 the SDK records for each file of one architecture; empty when it records none yet. */
export function checksums(classifier) {
  const recorded = readFileSync(join(SDK, "resources", "io", "github", "shiftinglabs", "codej", "sdk", "impl", "codex-checksums.properties"), "utf8");
  const found = new Map();
  for (const line of recorded.split("\n")) {
    const match = line.match(/^([^#=\s][^=]*)=([0-9a-f]{64})\s*$/);
    if (match && match[1].startsWith(`${classifier}/`)) {
      found.set(match[1].slice(classifier.length + 1), match[2]);
    }
  }
  return found;
}

export function sha256(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

/** Downloads OpenAI's npm package for one architecture into {@code work}, checks npm's checksum, and returns its vendor folder and integrity. */
export async function download(architecture, work) {
  const release = version();
  const metadata = await json(`https://registry.npmjs.org/@openai/codex/${release}-darwin-${architecture}`);
  const tarball = Buffer.from(await (await checked(fetch(metadata.dist.tarball))).arrayBuffer());
  const [algorithm, expected] = metadata.dist.integrity.split(/-(.*)/s);
  const actual = createHash(algorithm).update(tarball).digest("base64");
  if (actual !== expected) {
    throw new Error(`The npm package's ${algorithm} is ${actual}, not the registry's ${expected}`);
  }
  writeFileSync(join(work, "package.tgz"), tarball);
  run("tar", ["-xzf", join(work, "package.tgz"), "-C", work]);
  return { vendor: join(work, "package", "vendor", ARCHITECTURES[architecture].triple), integrity: metadata.dist.integrity };
}

/** Checks that {@code vendor} is Codex's own layout for the pinned release. */
export function checkLayout(vendor) {
  const layout = JSON.parse(readFileSync(join(vendor, "codex-package.json"), "utf8"));
  if (layout.version !== version() || layout.entrypoint !== "bin/codex" || layout.pathDir !== "codex-path") {
    throw new Error(`Unexpected Codex layout in ${vendor}: ${JSON.stringify(layout)}`);
  }
}

export function run(command, args) {
  const result = spawnSync(command, args, { stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed`);
  }
}

async function json(url) {
  return (await checked(fetch(url))).json();
}

async function checked(pending) {
  const response = await pending;
  if (!response.ok) {
    throw new Error(`${response.url} answered ${response.status}`);
  }
  return response;
}
