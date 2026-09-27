# AGENTS.md — talking-colonists

Minecraft mod that lets you talk to MineColonies citizens via Google Gemini AI voice chat.

## Multi-loader (Stonecutter)

Uses **Stonecutter** for two versions: `1.21.1-neoforge` (VCS default) and `1.20.1-forge`.

- Active version is in `.sc_active_version` (managed by Stonecutter).
- **Never commit changes to `.sc_active_version`** — the pre-commit hook blocks it. Use `--no-verify` only if intentional.
- Conditional compilation with `/*? if neoforge {*/` / `/*? if forge {*/` comments.
- Version-specific access transformers: `src/main/resources/aw/<mc version>.cfg` (e.g. `1.21.1.cfg`), used by both Forge and NeoForge. The `.accesswidener` files next to them are only for a Fabric build, which is not set up.
- Build scripts: `build.forge.gradle.kts` / `build.neoforge.gradle.kts`.
- Custom Gradle plugin `mod-platform` defined in `build-logic/`.
- When updating adding / removing configuration values in `McTalkingConfig`, make sure to update the `src/main/resources/assets/mc_talking/lang/en_us.json` translation file.
- ONLY put mixins in the `src/main/java/me/sshcrack/mc_talking/mixin` package. EVERY class in the `mixin` package MUST be a mixin (or accessor).

## Minecolonies Lookup
When working with the Minecolonies API, look at the `scripts/MINECOLONIES_DOCS.md` to view the docs and if you need actual code insight, use the gradle classes / minecolonies sources jar to view the source.

Before designing a feature that depends on how the game plays (building, upgrades, builders,
happiness, guards, raids, growth, day/night), read `docs/minecolonies-mechanics.md`: what really
triggers what in MineColonies and how long it takes. Check the design against it, for example
"a placed hut is level 0 until a builder builds it" or "a guard tower holds one guard at any level",
instead of leaving such mismatches for a playtest to find.

## Build & Run

```sh
./gradlew buildAndCollect                   # build + collect jars to build/libs/
./gradlew runActiveClient                   # run client for active Stonecutter version
./gradlew runActiveServer                   # run server for active version
./gradlew publishMods                       # publish to Modrinth/CurseForge
./gradlew publishModrinth                   # Modrinth only
./gradlew test                              # tests
./gradlew :1.21.1-neoforge:runGameTestServer :1.20.1-forge:runGameTestServer  # headless server GameTests (src/gameTest, see docs/automated-verification.md)
```

CI uses `./gradlew buildAndCollect --no-daemon`. JDK 25 (Microsoft) in CI.

## Review Subagents

For branch/release reviews that fan out across specialized review-only agents, use
`docs/agents/reviewers/README.md`. It defines the shared fixed-diff contract and the
separate spec, standards, API, lifecycle, Minecraft, Gemini/audio, memory/tools, and
validation lenses.

## Client Launch Smoke Test (mixin-scoped)

Purpose: the client smoke test exists to prove that **mixins inject correctly** on both
loaders — mixins fail loudly (and only) at real client startup, not in unit tests. It is
**not** a general runtime test; ordinary Java, prompts, lang files, and the public API
don't need a client launch to verify, and CI's real launch is the authoritative gate for
mixin correctness regardless of what runs locally.

### Fast local loop

- While iterating on ordinary (non-mixin) code: `./gradlew :1.21.1-neoforge:test`.
- Before pushing: run both loaders' suites, `./gradlew :1.21.1-neoforge:test :1.20.1-forge:test`.
  CI's `buildAndCollect` also builds the javadoc, so add `:1.21.1-neoforge:javadoc` when you
  touched Javadoc comments.
- Unit tests must not load Minecraft or MineColonies classes, not even through a lambda, a static
  initializer or a helper such as `NbtUtils`: CI's Forge run on JDK 25 fails with a
  `SecurityException` on signed classes, which does not reproduce locally. Keep MC types in
  GameTests (`src/gameTest`) and test pure logic.
- Only when mixin-relevant files change (see below): `bash scripts/test-client-smoke.sh`.
- Prompt text is snapshot-tested (`src/test/resources/prompt-snapshots/`). After an intended
  prompt change, regenerate with `UPDATE_PROMPT_SNAPSHOTS=1 ./gradlew :1.21.1-neoforge:test --tests '*PromptSnapshotTest' --rerun`
  and review the snapshot diff. Build prompt views in tests with `CitizenPromptViewFixture`.
- Optional, after prompt changes: `bash scripts/test-prompt-behaviour.sh` checks live model
  behaviour (3 cheap requests; key from the game config; skips without one).

### When the local gate applies

The pre-commit hook (`scripts/check-client-smoke-required.sh`) and the fingerprint
(`scripts/client-smoke-fingerprint.py`) only cover files that can affect mixin
application:

- `src/main/java/me/sshcrack/mc_talking/mixin/**` (the mixin classes themselves)
- Mixin configs, matched by suffix (`*.mixins.json`)
- `src/main/resources/aw/**` (access transformers/wideners — a bad AT/AW breaks mixin targets)
- `build-logic/**`, `build.forge.gradle.kts`, `build.neoforge.gradle.kts` (loader wiring
  and generated mod manifests)
- `settings.gradle.kts`, `stonecutter.gradle.kts`, `stonecutter.properties.toml`,
  `gradle.properties` (a MineColonies/loader dependency bump can break mixin targets)
- `.pre-commit-config.yaml` and the smoke scripts/fingerprint script themselves

Editing a prompt string, a lang file, a manager class, or the public API (`src/api`) does
**not** require the marker. `scripts/client-smoke-fingerprint.py`'s `relevant()` function
is the single source of truth for this set — the pre-commit check calls
`client-smoke-fingerprint.py changed` rather than duplicating the file list.

### Running it

```sh
bash scripts/test-client-smoke.sh
```

The smoke test:
1. Discovers every Stonecutter version (`1.21.1-neoforge`, `1.20.1-forge`). Pass a version
   as `$1` (e.g. `bash scripts/test-client-smoke.sh 1.21.1-neoforge`) to run only one —
   this is how CI's parallel matrix invokes it. A single-version run never writes the
   marker, since it has not verified every loader.
2. With no argument, runs all versions **serially** with `runClientAutoQuit` and one
   Gradle worker, so Minecraft/NeoForm downloads do not compete with each other.
3. Uses an existing singleplayer save when available. On a clean checkout with no
   save, auto mode opens Minecraft's vanilla create-world screen and creates a
   default disposable smoke-test world automatically. A title screen alone never
   counts as success.
4. Requires the explicit `MC_TALKING_AUTOQUIT_SUCCESS:world` marker. Auto mode closes
   itself only after the player has actually entered a world and remained there for
   60 client ticks, with an in-client startup deadline.
5. Watches the Minecraft/Gradle logs for crash markers and terminates the whole
   client process group immediately on a detected crash. A hard external timeout
   also terminates hung clients, so auto mode must not leave a crash/loading window
   open indefinitely.
6. Captures Gradle and Minecraft logs under `/tmp/client-smoke-*.log`. In headless
   environments it automatically uses `xvfb-run` when available.

If the sandbox can reach Mojang metadata but its asset CDN is blocked, use
`CLIENT_SMOKE_METADATA_ONLY_ASSETS=1 bash scripts/test-client-smoke.sh`. This still
launches the real client, creates/enters the smoke world, and exercises mixins/mod
construction; it only skips downloading cosmetic vanilla asset objects.

On success the script writes `.client-smoke-verified`, which contains a fingerprint of
the staged mixin-relevant content only. Stage the intended changes before running the
test, then stage the marker. Stonecutter may temporarily rewrite the active source
view during the launch; fingerprinting the index keeps that generated view out of the
verification contract:

```sh
git add <intended mixin-relevant changes>
bash scripts/test-client-smoke.sh
git add .client-smoke-verified
```

The pre-commit hook compares the marker with the staged mixin-relevant content and
blocks stale or missing verification, but only when mixin-relevant files are staged.
CI runs the real launch for both loaders as parallel matrix jobs (`client-smoke
(1.21.1-neoforge)` and `client-smoke (1.20.1-forge)`) and is the authoritative gate,
independent of the marker; a separate fast `smoke-marker-check` job checks the marker
against the committed tree over the same mixin-relevant set. This avoids the old
commit-hash race where a marker could only describe the parent commit rather than the
code being committed, and avoids requiring the marker (and the merge conflicts that
come with it) for changes that can't affect mixin injection.

`scripts/check-mixin-registration.sh` separately enforces mixin registration.

If a version fails, inspect the printed Gradle log and its adjacent `-minecraft.log`
copy. Do not fabricate or hand-edit `.client-smoke-verified`.

## Local Gemini Live Library

For local development the Gemini Live Library can be included as a composite build at `../gemini-live-library`, or pointed elsewhere with `GEMINI_LIVE_LIBRARY_DIR`. When a composite library is present, publishing tasks **fail** unless you confirm with:

```sh
./gradlew publishMods -PgeminiPublished=true
# or set GEMINI_PUBLISHED=true
```

## Addon API Compatibility Policy

The addon API (`src/api`, published as `me.sshcrack:mc_talking-api`) is **stable within API
generation 2. Do not make breaking changes to it.** Addons such as Colonist Errands build against it.

- Only additive changes: new types, new methods, new enum constants, new default interface methods.
  Never remove, rename, or change the signature, return type, or documented behaviour of an existing
  public API type or member, and never add abstract methods to interfaces addons may implement.
- New features must be feature-detectable (`TalkingColonistsApi` minor version / `ApiFeature`) so
  addons can degrade gracefully on older 2.x runtimes.
- Internal classes (everything outside `src/api`, including `internal/`, managers, config, mixins)
  may be refactored freely. Only the public API surface is protected.
- Before making any breaking change to the public API, stop and ask the user.
- `./gradlew checkApiCompatibility` (part of `check`, run in CI) compares the API jar with the
  published `mc_talking-api` version in `gradle.properties` (`api_baseline_version`) using japicmp:
  additions pass, removed/changed public members fail. Bump the baseline after each published
  release; never bump it to hide a break without the user's approval.
- Runtime compatibility for normal Talking Colonists users is separate from source compatibility
  for addon developers; both matter.

## Code Style

- 4-space indent for Java, 2-space for JSON/YAML/Markdown (`.editorconfig`).
- Java: single class imports; import-on-demand threshold = 999.
- No automated formatter or linter configured.

## Key Packages

| Path | Purpose |
|------|---------|
| `me.sshcrack.mc_talking` | Entrypoints: `McTalking` (common), `McTalkingClient` (client), `McTalkingVoicechatPlugin` (voice chat) |
| `.manager` | Gemini client (`GeminiWsClient`, `CitizenWsClient`), prompt providers |
| `.conversations` | Conversation lifecycle, memory management |
| `.mixin` | Mixins and accessors into MineColonies and Minecraft (25 classes) |
| `.config` | YACL-based config (`McTalkingConfig`), personalities, modes |
| `src/api` (`me.sshcrack.mc_talking.api`) | The public addon API (`TalkingColonistsApi`, `ApiFeature`, conversation, memory, prompt, tool, speech, guide, intro …); additive changes only |
| `.internal.api` | The runtime behind the API (`ConversationServiceBackend`, `ControlledConversationRuntime`, …) |
| `.conversations.construction`, `.onboarding`, `.rumor`, `.pregen` | Construction progress, handbook and introductions, gossip, pregenerated lines |

## Dependencies

Required: MineColonies (LDTTeam), Gemini Live Lib (`me.sshcrack`), Simple Voice Chat, YACL.
Embedded: MixinConstraints via JarJar.

## Publishing

- Tag must match `mod.version` + `mod.channel_tag` from `stonecutter.properties.toml`.
- `.env` file (gitignored, see `.env.template`) controls toggle flags and tokens.
- Dry-run by default; set `PUB_DRY_RUN=false` and `PUB_MODS_ENABLE=true` in `.env`.
- Modrinth project: `EOBBpcat` (repo-default, overridable via `PUB_MODRINTH_PROJECT_ID`).

## Release Workflow

1. Update `mod.version` in `stonecutter.properties.toml`.
2. Push a git tag matching the version.
3. CI validates tag, runs `buildAndCollect`, generates changelog (git-cliff), creates GitHub release, uploads artifacts.
