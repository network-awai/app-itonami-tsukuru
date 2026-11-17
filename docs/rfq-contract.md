# Tsukuru RFQ contract (AWAI-3244)

The minimal Alibaba-style B2B flow for the Tsukuru business bot and its Awai Android app.
Source: `src/tsukuru/rfq.cljc`. Tests: `test/rfq_test.cljs` (`npm test`).

## Flow

`draft` → `open` (publish) → quotes submitted / withdrawn → `awarded` (buyer picks one quote) → `ordered` (place-order with an approval bound to that quote).
`cancel` is allowed from `draft`, `open` and `awarded`. Any other event is refused with a reason; nothing is silently ignored.

- Inquiry (`itonami.tsukuru.rfq.v1`): id, buyer, title, HS code, quantity, unit, currency, Incoterm, delivery country, quote deadline, fulfillment (`stock` / `bto` / `mto` / `cto`), design artifacts (kind, name, SHA-256). Made-to-order modes need at least one artifact.
- Quote: supplier, unit price in micros, same currency and Incoterm as the inquiry, MOQ ≤ quantity, lead time, valid-until. One quote per supplier; a buyer cannot quote on their own inquiry.
- `ranked-quotes` sorts valid quotes by total, then lead time. It is advice for the bot's comparison view; the award stays the buyer's choice.
- `approval-request` is what the app must show before ordering. `place-order` checks that the recorded approval matches the buyer, inquiry, supplier, price, quantity, currency and total exactly. The purchase order (`itonami.tsukuru.purchase-order.v1`) is the result.

## Boundary

The domain is pure. Sending the inquiry to suppliers, receiving quotes and paying are external operations for the app layer (AWAI-3247). Each goes through the shared `kotoba.app.client-runtime.v1` broker with per-operation consent. There is no app-origin API.

## Placement decision

The domain lives in this repository, next to the bot profile and the Tauri app (AWAI-3246, AWAI-3247), following the one-bot-one-app pattern of `network-awai/app-itonami-rodo`.
The `cloud-itonami/cloud-itonami-marketplace-*` repositories model a B2C shop (listing, order, returns), not inquiry/quote/award, so extending them would mix two flows.

## Reuse points (not yet wired)

| Existing part | Used for |
| -- | -- |
| `cloud-itonami-marketplace-onboarding` | supplier credential before a quote is accepted |
| `cloud-itonami-marketplace-listing` | review of supplier catalogue entries |
| `cloud-itonami-marketplace-crossborder` | cross-border quote and dispute intake |
| `cloud-itonami/supplychain` | registering the resulting supply relationship |
| `cloud-itonami/app-itonami-hakken` | supplier discovery and price-gap signals for the bot |

## Legacy reference

`kotodama.edn` describes the earlier server-side supplier exchange (`alibaba-style-rfq-envelope`, `tsukuru-alibaba-supplier-rfq-json`, BPMN, RisingWave). Its artifact formats and the "missing design artifacts" blocker are carried over. The server runtime is not.
