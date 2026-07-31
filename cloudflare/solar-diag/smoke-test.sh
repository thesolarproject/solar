#!/usr/bin/env bash
# 2026-07-21 — Local smoke test for the solar-diag Worker via Miniflare (no deploy needed).
# Covers: /health config booleans, auth rejection (missing/bad token), invalid JSON,
# and the github_create_failed path that devices see as 502 when the GitHub PAT is bad.
# CI: run from cloudflare/solar-diag/ after `npm install` (miniflare is a wrangler dep).
set -euo pipefail
cd "$(dirname "$0")"

echo "==> syntax: node --check src/index.js"
node --check src/index.js

echo "==> miniflare smoke"
INGEST_TOKEN=smoke-ingest GITHUB_TOKEN=smoke-github node <<'NODE'
const { Miniflare } = require('miniflare');

function assert(cond, label, extra) {
  if (!cond) {
    console.error('FAIL ' + label + (extra ? ' — ' + extra : ''));
    process.exitCode = 1;
  } else {
    console.log('ok   ' + label);
  }
}

(async () => {
  const mf = new Miniflare({
    modules: true,
    scriptPath: 'src/index.js',
    bindings: {
      INGEST_TOKEN: 'smoke-ingest',
      GITHUB_TOKEN: 'smoke-github',
      GITHUB_REPO: 'thesolarproject/solar-diag',
      MAX_BODY_BYTES: '2097152',
    },
  });
  const base = 'http://localhost';

  // /health reports secret wiring (booleans only).
  let r = await mf.dispatchFetch(base + '/health');
  let body = await r.text();
  assert(r.status === 200, '/health 200');
  assert(body.indexOf('"ingest_token_configured":true') >= 0, 'health ingest flag');
  assert(body.indexOf('"github_token_configured":true') >= 0, 'health github flag');
  assert(body.indexOf('thesolarproject/solar-diag') >= 0, 'health repo');

  // Auth rejection: missing token -> 401.
  r = await mf.dispatchFetch(base + '/v1/report', { method: 'POST', body: '{}' });
  assert(r.status === 401, 'missing token -> 401');

  // Auth rejection: wrong token -> 401.
  r = await mf.dispatchFetch(base + '/v1/report', {
    method: 'POST',
    headers: { 'X-Solar-Diag-Token': 'wrong' },
    body: '{}',
  });
  assert(r.status === 401, 'bad token -> 401');

  // Invalid JSON -> 400.
  r = await mf.dispatchFetch(base + '/v1/report', {
    method: 'POST',
    headers: { 'X-Solar-Diag-Token': 'smoke-ingest' },
    body: 'not-json',
  });
  assert(r.status === 400, 'invalid json -> 400');

  // Good token, dummy GitHub token -> github_create_failed (what devices see as 502
  // when the worker GITHUB_TOKEN secret is missing/expired/bad scoped). The body must
  // stay JSON so SolarDiagClient can surface error + detail.
  r = await mf.dispatchFetch(base + '/v1/report', {
    method: 'POST',
    headers: { 'X-Solar-Diag-Token': 'smoke-ingest' },
    body: JSON.stringify({
      type: 'startup',
      trigger: 'routine',
      device: { model: 'smoke', sdk: 17, versionName: 'test' },
      summary: 'smoke',
      files: [],
    }),
  });
  body = await r.text();
  assert(r.status === 502, 'dummy github token -> 502 github_create_failed');
  assert(body.indexOf('"error":"github_create_failed"') >= 0, '502 body has error json');
  assert(body.indexOf('"detail"') >= 0, '502 body has detail');

  await mf.dispose();
})().catch((e) => {
  console.error('FATAL', e && e.stack ? e.stack : e);
  process.exit(1);
});
NODE

echo "OK: solar-diag worker smoke"
