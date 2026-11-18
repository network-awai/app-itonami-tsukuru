# hermes/ — retired Hermes profiles (AWAI-3246)

> **Retired 2026-11-18 (AWAI-3246).** `tsukuru-design-review`, `tsukuru-rfq` and
> `tsukuru-sim` are no longer the bots for this repository. Their
> `config.yaml` uses `model: provider: murakumo`. The workspace rule allows
> murakumo only on the itonami rail and on Awai Otehon Hermes profiles, so these
> profiles break it. Their RFQ role moves to the itonami business bot profile
> `bot-profile:cloud-itonami-isic-4610`
> (`profiles/business-bot-profile.json`) and the RFQ contract `src/tsukuru/rfq.cljc`.
> See `docs/bot-profile.md`.
>
> The files stay here unchanged as a record. `com-junkawasaki/root`
> `manifest/hermes-profile-repos.kotoba` still points at this path. Do not
> materialize, export or run these profiles again. Two things are still open:
> removing them from that manifest and stopping any host copy. A host copy is
> a live bot, so stopping it needs operator approval. Whether a host still runs
> them is unknown.

## Original description (historical)

This directory is the **source of truth** for the Hermes profiles listed below
(ADR-2609241200). The host's `~/.hermes/profiles/<profile>` is materialized
from `hermes/profiles/<profile>/` and checked against it:

```
kbb --backend sci scripts/hermes-profile-repo.cljk materialize <profile>   # repo -> host
kbb --backend sci scripts/hermes-profile-repo.cljk check <profile>         # 0 agree / 1 drift / 2 could not compare
kbb --backend sci scripts/hermes-profile-repo.cljk export <profile>        # host -> repo, then commit
```

(run from the com-junkawasaki/root superproject; registry
`manifest/hermes-profile-repos.edn`.)

Each profile directory holds SOUL.md, profile.yaml, config.yaml (host-local
blocks removed), cron/jobs.json (definitions only), scripts/ and the skills the
profile owns. **Never here:** `.env` or any secret value, workspace/ledgers,
sessions, memories, logs, caches, run state.

## Profiles

| profile | description |
|---|---|
| `tsukuru-design-review` | tsukuru 運営 fleet: 商品設計 sim/審査/RFQ 進行 |
| `tsukuru-rfq` | tsukuru 運営 fleet: 商品設計 sim/審査/RFQ 進行 |
| `tsukuru-sim` | tsukuru 運営 fleet: 商品設計 sim/審査/RFQ 進行 |
