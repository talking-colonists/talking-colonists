# Talking Colonists 2.1 — release notes

Changes since 2.0.0-beta.1. The GitHub release body is generated from commits by git-cliff; this
page is the curated summary to paste or link. Addon API: generation 2, `API_MINOR_VERSION` 1, fully
backward compatible with 2.0 addons.

## For players and server owners

### Colony Handbook and introductions

- **Colony Handbook:** when you first arrive in a colony, a citizen welcomes you and hands you a
  handbook. It has a chapter on talking to citizens and one chapter per installed addon. You can also
  craft one from a book and a paper. Your citizens know those chapters too, so you can simply ask
  them how something works.
- **Introductions:** citizens walk up to you once to tell you about the colony and the features your
  addons add. No more, even if you keep talking to them.

### Talking and typing

- **Type to citizens:** while talking to a citizen, start a chat line with `@` to send it to the
  citizen instead of server chat. `/citizen_chat on` sends every line. The prefix can be changed in
  the config. (Q10)
- **Talk without the device:** sneak and left-click a citizen with an empty hand to start or end a
  conversation. This can be turned off in the config. (Q9)
- **The speech capture shows a countdown** of how many seconds are left before the citizen answers.

### One voice at a time

Citizens no longer talk over each other or over you.

- While you talk to one citizen, **every other citizen's voice fades** to 30 % and comes back when
  you are done.
- Only **one citizen speaks at a time** within 32 blocks of a player. Citizens having a campfire or a
  pair conversation keep the floor between themselves, but still give way to you.
- **Citizens address a player at most every 150 seconds**, and ambient lines avoid repeating what
  the colony just said.
- If the model forgets to speak, the line is **retried once** and you hear it; a citizen that has
  audio queued never appears stuck in "thinking about a reply".
- A played greeting starts that citizen's cooldown, so you are not greeted again 15 seconds later.

### What citizens know and how they feel

- **Complaints that grow:** a lasting problem (no home, no job, sickness, idling) is mentioned in
  passing first, then complained about, then demanded. Young colonies get a grace period for
  housing, and **citizens of a new colony are hopeful rather than angry**. (Q5)
- **Citizens remember how often they complained to you**, and mention it less each time.
- **Citizens know how far along the colony's building jobs are**, what the builder is waiting for,
  and which citizen took a job on.
- **Gossip is visible:** you can see gossip moments happen and ask how far a piece of news has
  spread.

### Resting, sleeping and gathering

- **Sleeping citizens stay silent,** and housemates share rumors quietly overnight.
- **Campfire guests stop flipping between the fire and bed,** and a citizen mid-story is not told to
  go to sleep.
- Citizens in a controlled session **stay where they are** instead of wandering off, and
  **nobody walks up to you** while you are leaving a gathering.
- **No chats in bed,** no placeholder broadcasts, and only one welcome.

### Configuration

- **Config presets:** Free Tier (default), Paid Key and Quiet Colony set the session and chatter
  options at once. Changing one of those options afterwards shows Custom. See
  `docs/config-presets.md`. (Q8)
- **Personality picker:** choose which of the 15 archetypes new citizens can get. (Q7)
- **Less talking over each other:** a per-player ambient speech budget limits how many greetings,
  mumbles and rumors a player hears per minute. (Q4)
- **Clearer quota handling:** players and `/talking_colonists` show when Gemini's quota is used
  up (Q2). Operators are told when no API key is configured, with a working "Open config" link on
  both loaders (Q1).
- **Translatable strings throughout.**

**Config:** new entries are `configPreset`, `enableChatToCitizen`, `chatToCitizenPrefix`, the
`personality*` toggles, and the complaint settings. Existing values are kept. A config whose
session/chatter values differ from the defaults shows the Custom preset.

The Free Tier preset now matches the tuned defaults (8 % chance of an unprompted pair conversation
or a mumble, up from 5 %), so new installs are chattier out of the box. Paid Key's mumbling chance
moves from 8 % to 12 % to stay clearly above Free Tier.

## For addon developers (API 2.1)

Every feature below is implemented. Check `TalkingColonistsApi.supports(ApiFeature.X)` before
using it, so the addon still runs on 2.0.x. Details are in `docs/addon-api.md` ("API 2.1 at a
glance"). Replacements for 2.0-era internals are in `docs/addon-migration.md` ("Moving to API
2.1").

| `ApiFeature` | What it adds |
|---|---|
| `BROADCAST_PUBLISHING` | Publish and retract colony broadcasts (notice boards, newspapers) |
| `COLONY_EVENTS` | Read and record colony events, and listen to new ones |
| `TEXT_GENERATION` | In-character text from a citizen or the colony's voice, plain or JSON |
| `PLAYER_TEXT_INPUT` | Send typed lines and game-event notes into a player's live conversation |
| `UTTERANCE_EVENTS` | One event per finished player, citizen or scripted line |
| `PLAYER_CONVERSATION_OPTIONS` | Scoped conversations: agenda, tool allow-list, purpose, memory extraction |
| `PROVIDER_BUDGET` | Session capacity, per-model quota state, quota listeners, config view and reload |
| `VISITOR_SPEAKERS` | Opt-in policy that lets tavern visitors talk, with a visitor prompt view |
| `CROSS_COLONY_SESSIONS` | Controlled meetings with citizens from several colonies (same dimension) |
| `PLAYER_SPEECH_CAPTURE` | Turn what a player says through Simple Voice Chat into text, without a citizen |
| `BROADCAST_REACH` | How many citizens have heard a published broadcast, out of all of them |
| `ADDON_GUIDES` | Contribute a chapter to the Colony Handbook, which citizens can also be asked about |
| `INTRODUCTIONS` | Citizens walk up once to tell each player about the colony and addon features |

Also new: `ApiFeature` / `TalkingColonistsApi.supports(...)` / `requireSupported(...)` (A0), and
`ConversationLifecycleEvent.purpose()`.

**Not yet verified:** the in-world manual check for `PLAYER_SPEECH_CAPTURE` (listening indicator,
nothing captured after the end) is tracked in #149.

**Developer artifact:** `me.sshcrack:mc_talking-api:2.1.0-…` is published with the release. After
it is published, bump `api_baseline_version` in `gradle.properties` to it.
