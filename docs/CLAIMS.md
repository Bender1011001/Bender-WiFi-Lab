# App evidence by release

These records describe the Android preview. November 17 measurements apply to `0.5.5-latest-scan-preview1` (version code 18) unless another build is named.

| Observation | Evidence and scope |
| --- | --- |
| Installed over 0.5.4 on the rooted CPH2655 | The installed APK matched the release digest. Both inventory views opened. [Handset record](HANDSET-VALIDATION-055.md) |
| Latest scan and saved history matched the database | The preceding survey showed 16 networks and 9 observed device addresses. History showed 100 networks and 59 addresses. A new survey displayed its pending state. USB disconnected before completion and restoration could be checked. [Dated observations](../evidence/handset-preview055-20261117.json) |
| Software coverage | 96 JVM and 62 emulator tests passed. Results for 253 earlier backend checks were retained for unchanged inputs. [Build record](../evidence/bender-validation-055.json) · [Retained checks](../evidence/retained-backend-tests-055.json) |
| Device hints and counts | Hints have a recorded source. Vendor prefixes can identify a component maker, and observed addresses depend on traffic within the capture window. [Release notes](RELEASE-NOTES.md) |
| Earlier handset survey | The 0.5.4 survey and its restoration have their own dated [handset record](HANDSET-VALIDATION-054.md). |

One historical capture still reports an evidence-size warning and retains its raw evidence. The November 17 handset session tested inventory and scan startup. It did not qualify credential recovery or app-driven deauthentication. [Full observations and limitations](HANDSET-VALIDATION-055.md)
