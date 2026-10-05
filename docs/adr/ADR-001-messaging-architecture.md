# ADR-001: Messaging Architecture

**Status:** Proposed (pending user approval)
**Date:** 2026-10-04

## Context

JPH Messenger must connect a BlackBerry Bold 9790 (BB OS 7.1) to modern clients and Agent Zero. The BlackBerry speaks only legacy HTTP (TLS 1.0 best-case) over Wi-Fi/2G and cannot run modern protocols. Three architectures were evaluated.

## Decision Options

### Option A — Fully Custom Backend

BlackBerry → custom JPH backend → custom modern apps (iOS/Android/Web).

- Pros: no Matrix dependency; full protocol control; smallest possible server.
- Cons: **requires building modern clients from scratch** (violates "do not build custom modern clients before proving they are needed"); no federation; multi-device sync, receipts, groups all hand-built; largest total engineering effort.

### Option B — Pure Matrix Gateway

BlackBerry → JPH Legacy Gateway → Matrix → existing Matrix clients.

- Pros: modern clients for free (Element X, Fluffychat); federation optional; groups/media solved by Matrix.
- Cons: Matrix client-server API is heavy for a legacy gateway to mirror; full appservice bridging is overkill for one device.

### Option C — Hybrid (Recommended)

BlackBerry → **JPH Legacy API (compact, versioned)** → JPH Gateway → **Synapse (Matrix)** as message bus + user plane → existing Matrix clients; JPH Gateway also bridges to Agent Zero via an Agent Adapter.

- Pros:
  - BlackBerry speaks a **tiny custom protocol** optimized for 2G (compact JSON, cursor sync, offline queue) — no Matrix complexity on device.
  - Modern users get mature Matrix clients — no custom client work.
  - Gateway connects to Matrix as a **bot account via Client-Server API** (outbound only, no appservice inbound HTTP needed) — verified sane pattern.
  - Agent Zero integration is a parallel adapter on the gateway, not tangled into the messaging core.
  - Each layer independently replaceable.
- Cons: one more component (gateway) to maintain; message mapping logic between legacy API and Matrix.

## Comparison Summary

| Criterion | A Custom | B Pure Matrix | C Hybrid |
|---|---|---|---|
| Complexity | High (clients!) | Medium | Medium-low |
| Security | Full control | Matrix standard | Layered control |
| BB compatibility | Best | Worst | Best (custom legacy API) |
| Modern clients | ❌ build all | ✅ free | ✅ free |
| Offline messaging | Build | Matrix has it | Legacy API + queue |
| Groups/media later | Build all | ✅ built-in | Via Matrix mapping |
| Agent Zero integration | Direct | Via bridge | ✅ clean adapter |
| Operating cost | Low | Medium | Medium |

## Decision

**Choose Option C (Hybrid).**

1. **Homeserver: Synapse** (v1.162+, actively maintained by Element, PostgreSQL, native sliding sync, official Docker image, mature admin APIs). Dendrite rejected (maintenance mode); Rust servers (Conduit/Continuwuity/Tuwunel) rejected (no PostgreSQL, Element X compatibility uncertain).
2. **Gateway↔Matrix: bot account via CS API** (long-poll sync loop, outbound connections only). No appservice for the single-device PoC.
3. **BlackBerry↔Gateway: JPH Legacy API v1** — compact JSON over HTTP, device credentials, cursor-based incremental sync, idempotent message submission.
4. **Agent Zero: dedicated Agent Adapter** in the gateway, credentials server-side only, with permission model and confirmation flow.

## Consequences

- Gateway must maintain BB↔Matrix message mapping and device sessions.
- Matrix bot pattern must handle sync fallback (long-poll) efficiently.
- TLS 1.0 limitation of BB OS 7.1 must be handled at the gateway edge (see security skill/docs): the public internet must never see unencrypted legacy traffic; a controlled compatibility boundary (e.g., legacy TLS endpoint on gateway reachable only via VPN/WireGuard or LAN, or accepted-risk plain HTTP inside an encrypted tunnel) will be defined in `docs/security.md`.
- If Matrix proves too heavy later, the legacy API + agent adapter remain reusable (fallback toward Option A without the custom clients).

## Sources

- Matrix homeserver research report: `/a0/usr/workdir/matrix-homeserver-report-2026.md`
- matrix.org servers page; element-hq/synapse releases; element-hq/dendrite README (maintenance mode)
- BB OS 7.1 research: skills `blackberry-os7-*` (`.a0proj/skills/`)
