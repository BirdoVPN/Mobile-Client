# Connect contract (K5) — vendored

`vpn-protocol.schema.json` is a **byte-identical copy** of
`birdo-web/backend/contract/vpn-protocol.schema.json` (BirdoVPN/birdo-web,
PR #459; refreshed for the BirdoShield `dnsFiltering` flag in PR #465, and for
the Free allowance's `quotaExceeded` refusal and `quota_check_unavailable` 503
in #590, for the heartbeat `reason` field made optional in #599, for the
retryable `tunnel_address_unavailable` 503 in #711, and for the shared error
envelope (`ErrorEnvelope`, `RetryableDetails`, MR-1637) and the connect
refusal's typed `refusalReason` (`$defs.ConnectRefusal`, MR-139) in #739 —
birdo-web ccb459be, blob 6df1d62ea9efe1afaa03a7c094db6daa50f621ff). It is
GENERATED there (`cd backend && npm run contract:generate`)
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
| `iosApp/BirdoVPNTests/ConnectContractTests.swift` (iOS/macOS; on pull requests by `apple-ci.yml`, on release by `ios.yml`) | the real `ConnectBody` / `MultiHopBody` (`iosApp/Services/ConnectWire.swift`), encoded by the encoder `APIClient` uses, have keys that are a subset of the schema's properties, carry the required hop ids, and satisfy `type` / `minLength` / `maxLength` / `pattern` / `enum`; the same mutations are refused. The file reaches the test bundle as a resource (`project.yml`). |
| the same two files, heartbeat section (MR-1638) | every `POST /vpn/heartbeat/:keyId` reply birdo-web's `VpnService.heartbeat()` writes, for every `$defs.HeartbeatReason` value (the grace window on `ok` and `server_offline`, the removal records, the check-in cache's answers, both quota ends), validates against `$defs.HeartbeatResponse` and decodes with the app's model (Android `HeartbeatResponse`, judged by `HeartbeatPolicy` and `QuotaPolicy`; iOS `HeartbeatResult`, `iosApp/Services/HeartbeatWire.swift`); between them the bodies carry every property of the reply; a reason the model does not know, a property it does not read, or a reason with no decided handling fails; a reply with no reason, an unknown reason or unknown keys still decodes, and for every body an unknown reason is handled as none. |
| `app/src/test/java/app/birdo/vpn/data/repository/ConnectErrorContractTest.kt` (Android), connect refusal section (MR-139) | `ConnectRefusalReason` is exactly `$defs.ConnectRefusal`; a refused connect carrying each value validates against `$defs.ConnectResponse`, decodes with the app's model on both routes and gets the message and `FailureKind` its reason calls for (the device cap by the code, whatever the wording; `plan` offers View plans); a refusal with no value or an unknown one is handled by its wording, as before. |

Both routes refuse unknown properties (`additionalProperties: false`), so a
field renamed on either side is not ignored — it is a 400 on every connect.
That is what these tests turn from a production outage into a red CI job.
