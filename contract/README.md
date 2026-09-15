# Connect contract (K5) — vendored

`vpn-protocol.schema.json` is a **byte-identical copy** of
`birdo-web/backend/contract/vpn-protocol.schema.json` (BirdoVPN/birdo-web,
PR #459). It is GENERATED there (`cd backend && npm run contract:generate`)
from the code that enforces the wire contract — class-validator on
`ConnectDto` for `POST /vpn/connect`, the `.strict()` zod schema for
`POST /vpn/multi-hop/connect` — and pinned by `protocol-schema.spec.ts`, so a
backend contract change cannot merge without regenerating it.

**Never edit this copy.** Refresh it from birdo-web `main`:

```sh
# from this repo's root, with birdo-web checked out alongside
git -C ../birdo-web show origin/main:backend/contract/vpn-protocol.schema.json \
  > contract/vpn-protocol.schema.json
# prove it is byte-identical: both commands print the same blob hash
git hash-object contract/vpn-protocol.schema.json
git -C ../birdo-web rev-parse origin/main:backend/contract/vpn-protocol.schema.json
```

## What reads it

| consumer | what it proves |
|---|---|
| `app/src/test/java/app/birdo/vpn/data/model/ConnectContractTest.kt` (Android, `./gradlew :app:testDebugUnitTest`) | the real `ConnectRequest` / `MultiHopConnectRequest`, encoded by the app's own `NetworkModule.json`, validate against `$defs.ConnectRequest` / `$defs.MultiHopConnectRequest` with a Draft 2020-12 validator; every model field has a schema twin; an unknown key, a renamed key, a broken pattern and an explicit null are refused. |
| `iosApp/BirdoVPNTests/ConnectContractTests.swift` (iOS/macOS, `ios.yml` / `macos.yml` — iOS is never built in PR CI) | the real `ConnectBody` / `MultiHopBody` (`iosApp/Services/ConnectWire.swift`), encoded by the encoder `APIClient` uses, have keys that are a subset of the schema's properties, carry the required hop ids, and satisfy `type` / `minLength` / `maxLength` / `pattern` / `enum`; the same mutations are refused. The file reaches the test bundle as a resource (`project.yml`). |

Both routes refuse unknown properties (`additionalProperties: false`), so a
field renamed on either side is not ignored — it is a 400 on every connect.
That is what these tests turn from a production outage into a red CI job.
