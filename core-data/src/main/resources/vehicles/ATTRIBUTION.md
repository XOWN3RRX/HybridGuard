# Vehicle data attribution

The `signals.json` files in this folder are copied unchanged from the
[OBDb](https://github.com/OBDb) project and are licensed under the
[Creative Commons Attribution-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-sa/4.0/)
licence (full text: [LICENSE-CC-BY-SA-4.0.txt](LICENSE-CC-BY-SA-4.0.txt)).

| File | Source | Commit |
|---|---|---|
| `sae-j1979/signals.json` | [OBDb/SAEJ1979](https://github.com/OBDb/SAEJ1979) `signalsets/v3/default.json` | `d6689b23390a374ffbbf0821b2724efa53a4fcf0` |
| `toyota-corolla-hybrid-e210/signals.json` | [OBDb/Toyota-Corolla-Hybrid](https://github.com/OBDb/Toyota-Corolla-Hybrid) `signalsets/v3/default.json` | `1ddaca9e791bdd2a2e0a94d3a1418b0d29765cf7` |

Copied on 2026-11-13.

The `overrides.json` files are adaptations of that data (corrections found
on real cars) and are shared under the same CC BY-SA 4.0 licence. The
Corolla's `overrides.json` also adapts signal definitions from another
OBDb signalset, [OBDb/Toyota-Venza-Hybrid](https://github.com/OBDb/Toyota-Venza-Hybrid)
`signalsets/v3/default.json`, commit `ed1d214281bd2b03009a9be696060c4af5a7930f`
(read 2026-11-13), with the id prefix `VENZAHYBRID_` changed to
`COROLLAHYBRID_`: `7D2 22 1076` (READY), `7D2 22 1FA6` (odometer),
`7D2 22 15E8` (12 V statistics), `7D2 22 15E5` (12 V capacity),
`7D2 22 1060` (accelerator) and `747 22 1F5B` (battery-ECU SOC). And from
[OBDb/Toyota-Prius](https://github.com/OBDb/Toyota-Prius)
`signalsets/v3/default.json`, commit `6e7a842f3be58e2118f05a161168398d5c866089`
(read 2026-11-13), prefix `PRIUS_` changed to `COROLLAHYBRID_`:
`747 22 1802` (battery fan) and `747 22 180B` (battery intake air
temperature 1, converted from °F to °C); and from the same file at commit
`6fdf77141c95defa6214e2f7769785b98ccb4b16` (read 2026-11-14), same prefix
change: `700 22 1043` and `700 22 2827` (engine electric water pump).
`profile.json`, `derived.json` and `index.json` are HybridGuard's own files.

To update a signalset, replace the file with the newer upstream version and
update the commit hash above; keep local corrections in `overrides.json`.
