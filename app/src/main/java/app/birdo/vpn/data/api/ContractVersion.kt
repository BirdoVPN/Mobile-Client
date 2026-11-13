package app.birdo.vpn.data.api

/**
 * The client<->backend connect contract version this build was written against
 * (MR-197 / MR-1754): the `x-contractVersion` of the vendored
 * `contract/vpn-protocol.schema.json`, mirrored in `contract/CONTRACT_VERSION`.
 *
 * Sent as `X-Birdo-Contract` on every request ([AuthInterceptor]); the backend
 * counts connects by this value and by nothing else, and it changes no
 * behaviour. Bump it in the same commit that re-vendors the schema;
 * `ContractVersionTest` fails when the three disagree.
 */
const val CONTRACT_VERSION = "1.0.0"
