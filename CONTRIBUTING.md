# Contributing

Use JDK 17 and the SDK/NDK versions in README. Keep release ABI ARM64 and models outside Git. Use a separate debug ABI build when driving an x86 emulator.

Testing follows the **Test Android Apps** `android-emulator-qa` and `android-performance` skills. They provide an adb-driven workflow; they do not supply an Android device automatically. Select an actual adb target before claiming emulator/device results. Supporting JVM regressions and lint use:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

See `docs/TESTING.md` for instrumentation and QA/profiling. CI compiles/packages artifacts; it does not independently run a different test provider.

Keep inference sequential, buffers bounded, and model lifetimes explicit. Do not weaken source/voice cache invalidation or checkpoint durability to improve benchmarks. Avoid introducing timers on idle screens. Use targeted Room updates for progress.

For an optimization, report the exact flow, baseline and candidate revision, device/OS/build, run count, inputs, profiler evidence, and limitations. Emulator timings are not phone battery measurements. Commit test evidence only after reviewing it for private information. Keep APKs/traces in artifacts or releases rather than Git source history.

New original contributions use Apache 2.0. Preserve upstream notices and document third-party/model license changes.
