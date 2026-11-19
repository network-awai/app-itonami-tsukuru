# Tsukuru business bot profile (AWAI-3246)

## Profile

`profiles/business-bot-profile.json` is a copy of the canonical registry row
`bot-profile:cloud-itonami-isic-4610`. It comes from
`com-junkawasaki/root/90-docs/business/itonami-business-bot-profiles.json` at
revision `3ec76feea47948320d18fbe84496cf3ab9121468`, and the row was added in
com-junkawasaki/root#3805. Update the copy only from that registry, record the
new revision, and do not invent fields. `src/tsukuru/profile.cljc`
(`profile-problems`) checks the copy, and `npm test` runs that check.

| field | value | source |
|---|---|---|
| blueprint | `cloud-itonami-isic-4610` Wholesale on a fee or contract basis | `network-awai/app-itonami-cloud-itonami-isic-4610` `blueprint.edn` @ ebf75e2 |
| governor | `commission-broker-governor` | same |
| provisioning | `blueprint-only` | registry. A launch needs operator approval. |

### Why ISIC 4610

A commission broker arranges a trade but never takes title, and buyer and
supplier settle directly with each other. Tsukuru does the same: it helps a
buyer find suppliers and compare quotes, and the order is between buyer and
supplier.

Two other candidates were set aside:

- `unspsc-73`: its blueprint repo is now industrial-cleaning robotics.
- The manufacturing ISIC rows (2410, 2811, 3011): those are producers, not intermediaries.

## What the bot does

| | |
|---|---|
| bot drafts (`bot-tasks`) | `find-suppliers`, `draft-rfq`, `compare-quotes`, `summarize-quote` |
| user only (`user-events`) | every `tsukuru.rfq/transition` event |
| fresh approval for that exact operation (`approval-events`) | `publish`, `award`, `place-order` |
| not an operation of this app | payment |

A draft does not change an inquiry and is not sent to anyone. The app shows it, and the user decides.

## Inference

The bot uses the itonami rail and runs on the device first. A call to a remote
inference service is its own external operation and needs its own approval
through the shared client runtime. The murakumo fleet is down, so this app does
not use it. The registry's `runtime.model` (murakumo-main alias) describes
the DO plane for a launched bot. It is not the app's rail, and nothing is
launched.

## Legacy Hermes profiles: retired

Decision (AWAI-3246): **retire**, not replace.

| profile | where | rail | status |
|---|---|---|---|
| `tsukuru-design-review`, `tsukuru-rfq`, `tsukuru-sim` | `hermes/profiles/` in this repo | Hermes, `provider: murakumo` | retired. Files are kept as a record. |
| `tsukuru-scout` | `cloud-itonami/tsukuru-actor` | Hermes, murakumo | retired in this decision. That repo is not changed here. |

Reasons:

- The workspace rule allows murakumo only on the itonami rail and on Awai Otehon Hermes profiles. These profiles break that rule.
- Their RFQ queue role is now covered by the RFQ contract and the itonami profile above.
- Keeping them would mix the Hermes and itonami rails for the same business.

Still open (AWAI-3315 host/manifest status, checked 2026-11-19):

- Remove the four entries from `com-junkawasaki/root` `manifest/hermes-profile-repos.kotoba`, then regenerate the edn view. That manifest lives in another repository; it is not changed here.
- Stop any host copy under `~/.hermes/profiles/`. A host copy is a live bot, so this needs operator approval. This Mac: none — `ls ~/.hermes/profiles/` fails (no such directory) and `ls ~/.hermes/` shows only `config.yaml`, `logs`, `plugins`, so no `tsukuru-*` copy exists here. Other hosts: unknown; checking or stopping them needs operator approval.
- `cloud-itonami/tsukuru` holds another copy of the three profiles. It is not changed here; handling needs an owner decision.
