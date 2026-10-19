# Voice control plan: the whole app, by voice

**Goal.** Anything a sighted person can do by touch, a blind person can do by voice, said in whatever words come naturally.
Like Siri on a phone or Alexa in a house, the assistant has access to the whole app.

## How it works

One file is the source of truth: [`shared/build_voice_catalog.py`](../shared/build_voice_catalog.py). Run it and it writes

| Output | Used by |
|---|---|
| `shared/voice-commands.json` and `android/app/src/main/assets/voice-commands.json` | the app's parser (`assistant/VoiceCatalog.kt`) |
| `docs/VOICE_COMMANDS.md` | people: every command with every way of saying it |
| the website's "What you can say" section | users (`site/build.py` renders it) |

Because the app, the website and the documentation read the same list, **what is documented is exactly what works**.

A command is a list of **phrases** (the many ways to say the thing: 15 to 40 synonyms each, 2,000+ in all) and, for settings, a list
of **verbs** ("switch to", "change to", "turn on", "make it", "i want"... 43 of them). A sentence matches when it contains a phrase;
the verb is needed only for longer sentences, so "dark mode" and "please change to the black theme" both work. When two commands
both match, the **longest phrase wins** ("take a picture of me" beats "picture").

Safety nets, all checked by tests:

* the generator refuses to build if two commands could claim the same sentence (0 clashes today);
* `VoiceCatalogTest` feeds every phrase of every command, bare and after six verbs (over 4,000 sentences), through the real parser;
* phrases that are also ordinary things to say *about a photo* ("is it too bright?") are left out, so a question stays a question, and
  once there is a photo on screen a long sentence is never treated as an order;
* emergency commands (SOS) keep their short countdown and can be cancelled by voice.

## Coverage: everything the app can do

| Area | What the app does | Say (examples) | Status |
|---|---|---|---|
| **Wake** | Listen for the name | "Adanna", "Hey Bola", "Special request" | done; **Teach me your voice** in Settings adds how the phone hears *your* accent |
| **Stop / pause** | Silence, hold a scan | "stop", "pause", "hold on", "give me a moment" | done; on camera screens it then listens for the next request |
| **Open** | Home, Mirror, Read, Look around, Guide, Settings, Account, Back | "open the reader", "check my surroundings", "walk with me", "take me to the options", "back to the start" | done (7 destinations, 20 to 40 phrases each) |
| **Camera** | Take photo, **take a picture of me** (immediate), flip, front, back, look again, pause, resume | "snap it", "take a selfie", "switch to the front camera", "scan again", "carry on" | done |
| **After a description** | Repeat, retake, ask a question | "say that again", "retake", "I have a question" | done |
| **Sound** | Volume (up, down, max), speaking speed (slower, faster, normal) | "louder", "you're too fast", "full volume" | done |
| **Screen** | Brightness (up, down, full, auto), text size (bigger, smaller, huge, normal), font, theme (dark, light, system), contrast, motion, vibration | "make it darker", "increase the font size", "I can't read this", "stop vibrating" | done |
| **Descriptions** | Short, normal, detailed | "keep it short", "tell me everything" | done |
| **Listening** | Wake word, voice commands, stop-when-I-speak | "stop listening for my name" | done |
| **Voices** | Adanna or Bola, next voice, natural or phone voice | "switch to Bola", "use the man's voice", "try another voice" | done |
| **Language** | English, Hausa, Yoruba, Igbo, Ibibio | "speak Hausa", "reply in Yoruba" | done (Ibibio is read in English until a translation engine exists) |
| **Safety** | Where am I, I need help (SOS), send location, set / read / remove emergency contact, call 112 | "where am I", "I need help", "set my emergency contact to Mary" | done; contacts can also be typed in Settings |
| **Hands-free** | Keep listening after every answer | "access granted", "that's all" | done |
| **Weather and questions** | Ask the assistant | "what's the weather in Abuja" | done (any sentence that is not a command is a question) |

## Not controlled by voice, on purpose

* **Delete my account**, and **permissions** (Android shows its own box): too easy to trigger by mistake, and Android requires a touch.
* **Sign out** only opens the account screen, so it cannot happen by accident.

## Planned

1. "Teach Adanna my voice" as a spoken command (today: Settings, Teach me your voice).
2. Spoken **read the commands**: "what can I say about the camera?" reads that section's examples aloud (the catalog already has them).
3. Picking a numbered phone voice by number ("voice three").
4. Speech recognition for Hausa, Yoruba and Igbo **sentences** is wired through VoiceVerse (`/v1/transcribe`); it goes live when VoiceVerse deploys that endpoint.

## Adding a command or a synonym

1. Edit [`shared/build_voice_catalog.py`](../shared/build_voice_catalog.py): add words to a command's `phrases`, or a new `cmd(...)`.
2. `python shared/build_voice_catalog.py`. It rebuilds the JSON and the docs, and fails on a clash.
3. For a new command, add what it does in `VoiceSettings.fromCatalog` (settings) or `AssistantIntents.parse` (places and buttons).
4. `python site/build.py ...` refreshes the website section.
