# What a useful independent reproduction should record

Use a network and clients you are authorized to test. The published three-client result used V61 at 2437 MHz on one rooted CPH2655; newer source snapshots and the Bender APK need their own qualification. This checklist is about evidence quality, not a claim that any particular setup will work.

1. Record handset model, Android/kernel build, driver and firmware identifiers, root/runtime setup and exact tool hashes. State whether any external radio, custom driver or firmware modification is involved.
2. Record the selected AP/client relationship, actual operating frequency and AP security advertisement. If making a PMF claim, independently establish the client's negotiated PMF state; the beacon's capable bit is insufficient.
3. Establish a stable baseline with an observation channel separate from the sender. Router association logs are useful; add client connectivity measurements and, where available, an independent RF receiver. Exclude a noisy baseline from causal claims.
4. Use a bounded individual trial. Separate request submission, driver acceptance, matching TX status, observed client behavior and restoration in the result. An interrupted or uncertain transmission must not be automatically replayed.
5. Preserve the failed as well as successful outcomes, timestamps and clock uncertainty. Count userspace submissions separately from possible firmware retries. Do not infer exact RF timing from uncalibrated clocks or probe intervals.
6. Verify restoration and ordinary connectivity. Keep original private evidence, then prepare aliases and aggregate results for sharing. Avoid publishing passwords, PINs, raw household identifiers or personal screenshots.

## Compact report template

```text
Hardware / Android / kernel:
Driver / firmware / tool version and hashes:
Internal radio only? Root/runtime? Modified components?
AP security and frequency; negotiated PMF evidence if relevant:
Baseline and independent observation method:
Userspace submissions / matching TX status:
Router or receiver observations / client effect:
Restoration readback / gateway check:
Failed attempts and unresolved limitations:
Sanitized evidence link:
```

A negative result on another build is useful. It narrows compatibility; it should not be hidden by repeating requests until one appears to work. Public source/summary hashes facilitate comparison, but the private originals and instrumentation details remain necessary for deeper verification.
