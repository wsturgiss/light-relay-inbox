Build your own changes before handing them over. `./gradlew :tool:compileDebugKotlin` is the fast check; `./gradlew :tool:assembleDebug` produces a signed debug APK.

**Always** `JAVA_HOME=/home/will/.jdks/jbr-21.0.11` — system Java 26 breaks Android jlink.

Android SDK path comes from `sdk.dir` in `local.properties` (untracked).

Available icons: `sdk/ui/.../LightIcons.kt`. Page-level actions go on `LightBottomBar`, max 3. Prefer `LightIcons` / `LightBarButton.Text`.

## Working with `sdk/`

`sdk/` is vendored Light Phone `light-sdk`. **Do not hand-edit.** Consume public APIs only from `tool/`. If something is missing, work around in `tool/` or flag upstream.
