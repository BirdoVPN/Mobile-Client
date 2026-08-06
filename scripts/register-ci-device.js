#!/usr/bin/env node
// Register the current Mac with the developer account so xcodebuild can mint a
// development provisioning profile for it.
//
// macOS development profiles MUST name registered devices — creating one
// without them fails with "ENTITY_ERROR.RELATIONSHIP.REQUIRED: The relationship
// 'devices' is required" — and -allowProvisioningUpdates mints profiles but
// will not self-register a Mac, so it stops at:
//
//     Device "iad20-fj920-…" isn't registered in your developer account.
//
// A CI runner is a fresh machine every time, so this has to happen at runtime.
// Registration is idempotent: an already-known UDID comes back as a 409, which
// is treated as success.
//
// NOTE: every distinct runner consumes one of the account's 100 Mac device
// slots for the membership year. That is why the screenshot job is
// dispatch-only and never runs on push.
//
// Env: ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID
// Usage: register-ci-device.js <udid> [name]
const crypto = require('crypto');
const fs = require('fs');

const KEY_PATH = process.env.ASC_KEY_PATH;
const KEY_ID = process.env.ASC_KEY_ID;
const ISSUER = process.env.ASC_ISSUER_ID;

if (!KEY_PATH || !KEY_ID || !ISSUER) {
  console.error('ASC_KEY_PATH, ASC_KEY_ID and ASC_ISSUER_ID must all be set');
  process.exit(2);
}

const b64url = (b) => Buffer.from(b).toString('base64')
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function token() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'ES256', kid: KEY_ID, typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    iss: ISSUER, iat: now, exp: now + 900, aud: 'appstoreconnect-v1',
  }));
  const signingInput = `${header}.${payload}`;
  // ES256 must carry the signature as raw r||s; Node's default DER encoding is
  // what makes App Store Connect answer 401 with no explanation.
  const sig = crypto.sign('sha256', Buffer.from(signingInput), {
    key: fs.readFileSync(KEY_PATH, 'utf8'),
    dsaEncoding: 'ieee-p1363',
  });
  return `${signingInput}.${b64url(sig)}`;
}

(async () => {
  const udid = process.argv[2];
  const name = process.argv[3] || `CI runner ${udid.slice(-12)}`;
  if (!udid) {
    console.error('usage: register-ci-device.js <udid> [name]');
    process.exit(2);
  }

  const res = await fetch('https://api.appstoreconnect.apple.com/v1/devices', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      data: {
        type: 'devices',
        attributes: { name, platform: 'MAC_OS', udid },
      },
    }),
  });

  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }

  if (res.status === 201) {
    console.log(`registered ${name} (${udid})`);
    return;
  }

  // Already registered is the normal case on a re-run, not a failure.
  const errors = (json && json.errors) || [];
  if (res.status === 409 && errors.some((e) => /already exists|taken/i.test(e.detail || e.title || ''))) {
    console.log(`already registered (${udid})`);
    return;
  }

  console.error(`register failed ${res.status}: `
    + (errors.map((e) => `${e.code}: ${e.detail || e.title}`).join(' | ') || text.slice(0, 300)));
  process.exit(1);
})().catch((e) => { console.error(e.message); process.exit(1); });
