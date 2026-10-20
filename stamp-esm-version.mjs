// Give a source-built ESM a version unique to this image build, so `openmrs assemble`
// serves it from a path no CDN has cached. Dockerfile.frontend's chartsearchai-esm stage
// says why; every ESM it builds from source runs this, from the clone's root, after
// `yarn install` and before `yarn build && yarn pack`.
//
// Usage: node stamp-esm-version.mjs   (reads ESM_SHA and package.json from the cwd)

import { readFileSync, writeFileSync } from 'node:fs';

const p = JSON.parse(readFileSync('package.json', 'utf8'));
const sha = readFileSync('ESM_SHA', 'utf8').trim().slice(0, 12);
const build = (process.env.CACHE_BUST || '').replace(/[^0-9A-Za-z]/g, '') || String(Date.now());
p.version = p.version.split('-')[0] + '-git' + sha + '.r' + build;
writeFileSync('package.json', JSON.stringify(p, null, 2) + '\n');
console.log('ESM version', p.version);
