# Locus 4.36 fixture and steps ingress probe

Locus [announced its phone step counter in 4.36.0](https://help.locusmap.eu/announcement/locus-map-android-version-4-36-092026).
That announcement does not document an app-fed steps input. This review tested two candidate
`DATA_TASK` payloads; it does not change the production bridge.

## Fixture

The regular Google Play Locus Map APK is `4.36.0_1217_release.apk` from the
[official 4.36 distribution folder](https://drive.google.com/drive/folders/1Z7_6a1D6Wx1OjJcOj2KbmfVwE5zhJHyE).
It was downloaded into a private directory outside the repository. The pinned
`tools/download-locus-apk` command independently fetched it unattended and produced the same
SHA-256, `8968b25633594fa45ab60b8336328fec2dffd5d07ed29a0996d9cedf55898c12`.
Its size is 144,117,863 bytes. Android metadata and `apksigner` verified package
`menion.android.locus`, version 4.36.0 (1217), minimum SDK 24, target SDK 37, x86_64 native code,
and signing certificate SHA-256
`d6298f36bed25632ddc31bfb9b8aba32a0f3ffc2edfece4fd5d7cf6c1840a5bc`.
The pinned fixture validator accepted it for the API 34 emulator.

## Probe

The temporary probe used the same `com.asamm.locus.DATA_TASK` broadcast and `tasks` extra as the
production heart-rate sender. A known heart-rate payload was the transport control. Each step
candidate was to be sent during its own recording, with Locus's periodic
`TrackStats.numOfStrides` polled for read-back. A successful broadcast call by itself was not
counted as Locus acceptance.

The first hosted attempt was [run 36620254740](https://github.com/ChristianHerget/trackglance/actions/runs/36620254740),
commit `a38eb0338575944753051a1602422a00297366fe`, published fresh lifecycle. Provisioning
completed, but Android instrumentation reported 72 tests and one failure: the isolated probe's
`{heart_rate:{data:123.0}}` control was not observed within 25 seconds. The test stopped that
recording; it did not send either steps payload. The temporary test also failed static formatting.
This attempt gives no evidence about steps ingress. The normal Android stage disables location for
its fake-location tests; whether that caused the absent heart-rate reading is unknown. A second
probe was placed after the established Emery heart-rate acceptance path with location active.

The second hosted attempt was [run 36621829969](https://github.com/ChristianHerget/trackglance/actions/runs/36621829969),
commit `40ef05d79f219c508b065d04893e1f4cae0d8aba`, published fresh lifecycle. Static,
documentation, release packaging, Android instrumentation, Emery, and Gabbro all passed. The Emery
probe ran as part of that success, but its numerical readings stayed in the stage log, which the
successful job did not publish. A further hosted run used the same probe with a temporary step to
print only the `LOCUS_STEPS_PROBE` lines.

The third hosted attempt, [run 36626059791](https://github.com/ChristianHerget/trackglance/actions/runs/36626059791),
commit `6bb3f49`, also passed static, documentation, release packaging, and Android
instrumentation. Emery's acceptance component passed with location, Pebble App onboarding, relay,
and settings ready. Its temporary log-reporting step then failed because the runner lacks `rg`;
the resulting sanitized diagnostics artifact contains the full Emery stage log.

The probe ran after Emery's normal watch-to-Locus heart-rate acceptance. In each of two separate
recordings, a `{heart_rate:{data:137.0}}` control sent through `DATA_TASK` appeared as Locus's
current heart rate before the step candidate was sent. The exact periodic readings were:

| Recording | `DATA_TASK` payload | `TrackStats.numOfStrides` before | Readings during repeated sends |
| --- | --- | ---: | --- |
| 1 | `{strides:{data:37.0}}` | 0 | `0, 0, 0, 0, 0, 0` |
| 2 | `{steps:{data:53.0}}` | 0 | `0, 0, 0, 0, 0, 0` |

Each candidate was sent repeatedly within a bounded 24-second observation window, and each
recording was stopped and saved separately. Neither candidate produced a nonzero periodic value,
so there was no positive value for a saved-recording step-total check. Broadcast delivery is not
evidence of Locus acceptance. These observations do not prove an app-fed steps ingress through
either tested payload; they do not rule out another supported API or payload. Issue
[#13](https://github.com/ChristianHerget/trackglance/issues/13) remains blocked pending Locus
documentation or maintainer confirmation of supported ingress.

The same run's Gabbro job failed in `suite-provisioning` after 456 seconds, before watch
acceptance. The bootstrap log shows `UiTestAutomationBridge` returning a null root, followed by
missing or stale UI dumps and `Timed out waiting for Android UI text: START`. This is a harness
onboarding race, not evidence that Locus rejected the APK: the same fixture passed Gabbro in the
preceding fresh hosted run. The final revision clears stale dumps, rechecks the map when `START`
disappears before a tap, and adds focused regressions for both observed races.
