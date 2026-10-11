# Config presets

The **Preset** entry at the top of the config's API tab (`configPreset` in
`config/yacl-mc_talking.json5`) sets the entries below all at once.

- **Free Tier** (default) matches the field defaults. It fits a free AI Studio key, which allows
  3 concurrent Live sessions (2 foreground + 1 background) and only about 10 TTS requests a
  day. `AUTO` conversation mode falls back to Live sessions once TTS is rate-limited.
- **Paid Key** allows more concurrent sessions, more ambient chatter and conversation summaries.
- **Quiet Colony** keeps citizens silent unless a player talks to them. Mumbling,
  citizen-to-citizen conversations, voiced rumors and broadcasts, greetings and urgent
  contact are all off. Player conversations, memory and rumor spreading (not voiced) still
  work.
- **Custom** means the values were changed by hand. Choosing it writes nothing.

In the config screen, choosing a preset fills in its values right away. Changing any entry
in the table afterwards switches the preset to **Custom**. Entries not in the table are never
touched by a preset.

On a dedicated server, edit `configPreset` in the JSON file and restart. On load the
server compares it with the internal `appliedConfigPreset`. If they differ, it
writes the preset's values. If they are equal but a value in the table was edited, it
switches the preset to `CUSTOM`.

| Key | Free Tier | Paid Key | Quiet Colony |
|---|---|---|---|
| `maxConcurrentAgents` | 2 | 6 | 2 |
| `maxConcurrentBackground` | 1 | 3 | 1 |
| `conversationMode` | AUTO | AUTO | AUTO |
| `enableConversationSummaryAndMemorize` | false | true | false |
| `enableCitizenToCitizenConversation` | true | true | false |
| `enableRandomConversations` | true | true | false |
| `randomConversationChance` | 0.08 | 0.1 | 0.08 |
| `randomConversationCheckIntervalTicks` | 400 | 300 | 400 |
| `mumblingChance` | 0.08 | 0.12 | 0.0 |
| `mumblingCheckIntervalTicks` | 200 | 160 | 200 |
| `enablePregeneration` | true | true | false |
| `enablePlayerGreetingPregen` | true | true | false |
| `enableCitizenInitiatedContact` | true | true | false |
| `enableRumorTalking` | true | true | false |
| `rumorTalkingChance` | 0.5 | 0.7 | 0.5 |
| `enableBroadcastYelling` | true | true | false |
| `ambientSpeechBudgetMaxLines` | 3 | 5 | 3 |
| `citizenCooldownSeconds` | 120 | 60 | 120 |

The table is checked against `ConfigPreset` by `ConfigPresetTest`.
