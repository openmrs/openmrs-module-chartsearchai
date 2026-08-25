// Pre-compress an assembled OpenMRS SPA in place, writing the .gz and .br
// siblings its nginx expects.
//
// The frontend image's nginx answers a request with <file>.br or <file>.gz when
// one exists, chosen from Accept-Encoding by a map in its config (br is tried
// first, so it is the variant a browser actually receives). It ships such
// siblings for its OWN assembly — measured 2026-08-25: 3900 .gz and 3900 .br —
// while `openmrs assemble` emits none. Without this script the siblings beside
// the assembled files are therefore the base image's, and they answer for
// content that no longer exists. See Dockerfile.frontend for what that cost.
//
// A sibling is kept only where it is actually smaller than its source, so no
// extension allow-list is needed and none can drift out of date.
//
// Brotli runs at quality 9, not the maximum. Measured 2026-08-25 on a 250-file,
// 12.6 MB spread taken across a real SPA tree of 6554 compressible files, with
// the time column scaled to that whole tree:
//
//   q9   2.55 MB    ~20s        gzip -9, for comparison: 2.82 MB
//   q10  2.31 MB   ~149s
//   q11  2.25 MB   ~418s
//
// Confirmed on the real thing rather than left as an extrapolation: over this
// image's own 6031-file assembly, both encodings together take 61s at q9,
// against 354s for the q11 build it replaced.
//
// So the maximum costs about five extra minutes of every image build — and this
// image is rebuilt on every push to main — to land 2.6% under q10. What these
// siblings have to be is CURRENT: a stale one is the defect Dockerfile.frontend
// describes, and no compression level protects against it. The demo also sits
// behind Cloudflare, which re-encodes, so the origin's brotli level barely
// reaches a browser there at all; it only reaches a deployment served directly.
// Raise it here if that changes, and re-measure rather than assuming these
// numbers still hold.
//
// index.html is skipped deliberately: the image's startup.sh rewrites it at
// container start (envsubst of SPA_PATH, API_URL, SPA_CONFIG_URLS, …), so a
// pre-compressed copy would shadow the substituted file in exactly the way
// this script exists to prevent. Today `openmrs assemble` emits no index.html
// at all — the base image supplies it — and this keeps the invariant if that
// ever changes.
//
// Each sibling is stamped with its source's mtime. Dockerfile.frontend's guard
// reads that to tell a sibling that came from the assembly apart from one left
// behind by the base image, which it cannot do by content because the final
// stage has no brotli decompressor.
//
// Usage: node precompress-spa.mjs <dist-dir>

import { constants, brotliCompressSync, gzipSync } from 'node:zlib';
import { readdirSync, readFileSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SKIP_NAMES = new Set(['index.html']);
const SKIP_EXTENSIONS = ['.gz', '.br'];
const BROTLI_QUALITY = 9;
const GZIP_LEVEL = 9;

const dist = process.argv[2];
if (!dist) {
  console.error('usage: node precompress-spa.mjs <dist-dir>');
  process.exit(2);
}

function* files(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* files(path);
    else if (entry.isFile()) yield path;
  }
}

const encoders = [
  { extension: '.gz', encode: (buf) => gzipSync(buf, { level: GZIP_LEVEL }) },
  {
    extension: '.br',
    encode: (buf) =>
      brotliCompressSync(buf, {
        params: {
          [constants.BROTLI_PARAM_QUALITY]: BROTLI_QUALITY,
          [constants.BROTLI_PARAM_SIZE_HINT]: buf.length,
        },
      }),
  },
];

let considered = 0;
const written = Object.fromEntries(encoders.map((e) => [e.extension, 0]));
const startedAt = Date.now();

for (const path of files(dist)) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  if (SKIP_NAMES.has(name)) continue;
  if (SKIP_EXTENSIONS.some((extension) => name.endsWith(extension))) continue;

  const source = readFileSync(path);
  const { atime, mtime } = statSync(path);
  considered++;

  for (const { extension, encode } of encoders) {
    const encoded = encode(source);
    // Serving a sibling that is larger than its source costs bandwidth rather
    // than saving it, so it is simply not written.
    if (encoded.length >= source.length) continue;
    const sibling = path + extension;
    writeFileSync(sibling, encoded);
    utimesSync(sibling, atime, mtime);
    written[extension]++;
  }
}

const summary = encoders.map((e) => `${written[e.extension]} ${e.extension}`).join(', ');
console.log(
  `precompressed ${considered} file(s) under ${dist}: ${summary}` +
    ` in ${Math.round((Date.now() - startedAt) / 1000)}s`,
);
