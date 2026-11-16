# Controlled PMF counter test — archived preparation status, 2026-11-14

This is a historical preparation snapshot. The completed trial and cleanup are
documented in [CONTROLLED-TEST-RESULTS.md](CONTROLLED-TEST-RESULTS.md). The pending
actions and blockers below describe the earlier state, not the current setup.

**The external sender is qualified for a directed probe exchange. No PMF rejection
trial has been submitted, and no counter increment has been demonstrated.** The
remaining setup step is an observed association of the OnePlus to Bender-PMF.

## Observed setup

The user identified an ALFA AWUS036ACS and a WNC-CR200A router, then enabled a
temporary 5 GHz-only guest network with WPA2/3 and connected only the OnePlus.
The phone reported completed SAE at5805MHz/channel161. Its last-used security
record required PMF. These endpoint records do not themselves establish rejection
of an invalid management frame. A direct supplicant STATUS query timed out.

The Alfa USB ID0bda:0811 was passed into WSL Ubuntu. The running kernel remains
6.6.87.2-microsoft-standard-WSL2. Matching kernel sources and the maintained rtw88
driver were built locally; no new kernel was installed or started. All203cfg80211
and340mac80211 imported-symbol CRC comparisons matched the installed modules.
Normal module loading subsequently accepted the driver. The USB firmware reports
42.4.0/H2C0. The exact source commits, build configuration differences, module
hashes and failed setup attempts are retained privately.

WSL needed the stock firmware/database files made available through its shared
mount. The temporary firmware search-path setting was verified restored. The
driver remains temporarily loaded for this experiment. No country override,
forced module loading, phone firmware change or primary-router security change
was performed.

The Alfa was given a temporary locally administered address. A passive scan on
5805MHz observed the exact guest BSSID at-50dBm, with a228ms last-seen age, SAE/PSK
and MFPC. Normal beacon handling cleared the channel's no-IR flag; the global
regulatory domain remained00. Monitor reception then captured guest traffic.

## Raw transmit qualification

One directed probe request for Bender-PMF was submitted, with no application
retry. The sender returned58bytes accepted. The capture contains a probe response
addressed to the temporary Alfa address, followed by three retries with the same
sequence number. The first response was observed after the recorded submission.
This supports raw probe transmission and reception of the AP's response on this
setup. It does not qualify every frame family or independently establish arrival
of a later PMF trial frame at the handset.

## Association observation and current blocker

The first two passive windows contained no association/authentication exchange.
One ordinary phone-side `iw disconnect` request returned EPERM; it was not
blindly repeated. A subsequent Android framework Wi-Fi off/on cycle restored Wi-Fi
to enabled, but Android selected the original network at6135MHz. That connection
is outside the Alfa's supported bands. No PMF trial was attempted against it.

The user was asked to select Bender-PMF again. A bounded, guest-filtered capture
was opened for that association. The trial must use fresh matching phone and AP
state; the earlier5GHz endpoint record is not current after the framework cycle.

## Prepared experiment and validation

- A read-only phone sampler holds the shared radio lock, checks the exact test
  association, takes six baseline samples and then a bounded observation window.
  Six offline fault tests passed, including changed-target, missing-counter and
  unstable-baseline cases.
- The sender persists intent before one submission and refuses reuse after an
  uncertain result. Independent TShark dissection checked the generated probe
  and group-deauthentication formats, rate and no-ACK flag. These were offline
  fixtures; the PMF fixture was not transmitted.
- The planned PMF trial uses one group deauthentication frame without an MMIE.
  A zero delta without arrival/rejection evidence remains inconclusive. The
  one-frame initial trial does not prohibit later deliberate, counted repeats.
- The association classifier and trial coordinator are prepared and syntax
  checked, but have not completed a live PMF run. Counter accuracy, freshness and
  reset semantics remain unqualified.

Private evidence and scripts are under `validation/pmf-20261114/controlled/` and
WSL `/opt/bender-pmf-20261114/`. The current continuation state is in the former
directory's `CHECKPOINT.md`. No released-app code or capability claim was changed.

At the end of the experiment, stop capture, restore the Alfa's USB attachment and
temporary module/interface state, and disable the guest test network unless the
user chooses to retain it. Until then, the current sender setup is retained for
the requested test.
