# Nullness checks (NullAway)

The build runs [Error Prone](https://errorprone.info/)'s
[NullAway](https://github.com/uber/NullAway) checker over the mod's own packages to catch
unguarded dereferences of `@Nullable` values before they turn into a server crash like
[#286](https://github.com/talking-colonists/talking-colonists/issues/286).

It is **warning-only**. A NullAway finding prints a `warning: [NullAway] ...` line next to
the offending source line but never fails a build, so a stale annotation cannot block
development or CI.

## What is checked

- Only classes under `me.sshcrack.mc_talking` (`-XepOpt:NullAway:AnnotatedPackages`), including
  the addon API in `src/api`.
- All other Error Prone checks are disabled; NullAway is the only analyzer enabled.

Minecraft and MineColonies are unannotated, so NullAway cannot see their nullability
contracts. When such a boundary can return null, add a checked helper (for example
`CitizenHelper.findCitizen`) and annotate it instead of relying on the external method.

## Running it

```sh
./gradlew :1.21.1-neoforge:compileJava --rerun-tasks
./gradlew :1.20.1-forge:compileJava --rerun-tasks
```

Incremental builds only recompile changed files, so a normal `compileJava` can hide existing
warnings. Use `--rerun-tasks` (or `clean`) when checking the whole tree:

```sh
./gradlew :1.21.1-neoforge:compileJava :1.20.1-forge:compileJava --rerun-tasks --no-daemon \
  2>&1 | grep 'warning: \[NullAway\]' | sort -u
```

Keep the output empty.

## Annotating

The project uses the JetBrains annotations already on the classpath
(`org.jetbrains.annotations.Nullable` / `NotNull`).

- Mark a method, field, parameter or record component `@Nullable` when it can genuinely be
  null. Prefer fixing the annotation over suppressing the warning.
- Guard an `@Nullable` value with a null check before dereferencing it. Restructure ternaries
  and stream chains so the compiler can follow the check (assign to a local first).
- Unit tests must not load Minecraft or MineColonies classes, so do not add nullness
  annotations that require those types in tests.

### Suppressing injected or reflective state

NullAway assumes fields are initialized by constructors. Two kinds of fields are not:

- Mixin `@Shadow` fields (filled by Minecraft/MineColonies) and `@Unique` state fields whose
  null start is meaningful. State fields that start as null should simply be `@Nullable`;
  fields the host class fills are covered by a class-level

  ```java
  @SuppressWarnings("NullAway.Init") // @Shadow fields are injected by MineColonies.
  ```

- Gson DTO fields filled by reflection (for example `GsonMemoryResponse`) use
  `@SuppressWarnings("NullAway.Init")` on the DTO class because absent JSON keys leave the
  fields null.

Do not suppress a real dereference warning; fix it. `@SuppressWarnings("NullAway")` on
business logic hides exactly the crash class this check exists to find.

## Build wiring

The checker is configured in `build-logic/src/main/kotlin/ModPlatformPlugin.kt`
(`configureNullnessChecks`). Error Prone and NullAway are pinned because newer Error Prone
releases require a newer JDK than the Forge toolchain (JDK 17):

- `com.google.errorprone:error_prone_core:2.42.0`
- `com.uber.nullaway:nullaway:0.14.3`

On Forge, the Mixin annotation processor is a fat jar that bundles an old, unrelocated Guava
copy. Error Prone needs Guava 31+, and javac loads processor-path classes from the first jar,
so the Error Prone dependencies are placed before the source set's other processors on
`annotationProcessorPath`. Do not reorder that path.

JavaCompile tasks that are not backed by a source set (the API-example showcase) disable the
checker; they compile addon code, which is not part of `AnnotatedPackages`.
