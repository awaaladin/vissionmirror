# VisionMirror: from a talking mirror to a voice assistant for blind and low-vision people

Version at time of writing: **0.8.0** (Android app) and the matching backend. Download: <https://visionmirror-app.vercel.app>

---

## 1. The short version

**Where it started (0.1.0).** A talking mirror. A blind person held the phone at arm's length, the app guided them into frame by voice and vibration, took a photo, and an AI described their outfit and hair and flagged anything to fix (a stain, a crease, clashing colours). They could ask follow-up questions about the photo.

**Where it is now (0.8.0).** A hands-free voice assistant with a name. A person says **"Adanna"** (or **"Bola"**, the male voice) and the app does what they ask: reads a label, describes what is in front of them, answers questions, checks the weather, changes any setting, and takes control of the whole app by voice without a single touch. It speaks in a natural Nigerian voice, copes with weak networks, and can be interrupted at any time by saying "stop".

| | Then (0.1.0) | Now (0.8.0) |
|---|---|---|
| Purpose | Check how I look | Do what I need, by voice |
| How you use it | Touch buttons | Say "Adanna …", or tap the orb, or go fully hands-free |
| What it can look at | You (front camera) | You, writing, and your surroundings |
| What it can answer | Questions about your photo | Photo questions, weather, general questions, time and date |
| Voice | The phone's default voice | Nigerian English voice (cloud) with the phone's voice as fallback; woman (Adanna) or man (Bola) |
| Languages | Device language | Nigerian English, plus Hausa, Yoruba, Igbo and Ibibio through the VoiceVerse translation API (needs a key, see section 7) |
| Settings | Tap through screens | Every setting can be changed by speaking |
| Bad network | Often failed | Silent retries, offline text reading, clear spoken status |

---

## 2. Version history

| Version | What changed |
|---|---|
| **0.1.0** | First download: mirror guidance, photo description, follow-up questions. |
| **0.2.0** | Accounts (sign up, sign in, delete), text size and font choice, voice picker, camera flip, pause, developer tools. |
| **0.3.0** | Nigerian English voice and writing style, cloud voice endpoint, Hausa/Yoruba/Igbo/Ibibio translation hook, the "special request" keyword, network retries, a clearer download page. |
| **0.4.0** | The assistant **Adanna**: wake word, the Look tools (Read, Colour, Look around), a Home screen, "look around" scene mode on the API. |
| **0.5.0** | Voice control of every setting, screen brightness, the new VisionMirror logo, the orb-style Home screen. |
| **0.6.0 to 0.6.2** | Weather and general questions (`/v1/assist`), full-screen camera with a scanning line, live "what I hear" display, a more reliable microphone (fallback recogniser, patient listening), the Colour tool removed on request. |
| **0.7.0 to 0.7.1** | "Stop" while she is talking, hands-free mode ("access granted"), commands for the open screen, time and date, one listener that works on every screen, pause and "go on" on the camera screens, 8 seconds of patience before "I can't hear you". |
| **0.8.0** | **Bola**, the male voice, and switching between Bola and Adanna by voice. Tap anywhere on a camera screen to pause and talk. |

---

## 3. What the assistant can do now

### 3.1 Two names, two voices
- **Adanna** (default, woman's voice) and **Bola** (man's voice).
- Say "switch to Bola" or "male voice"; say "switch to Adanna" or "female voice". There is also a choice in Settings.
- Both names wake the app. The active name is shown on Home and used in greetings.

### 3.2 Always ready, on every screen
- While the app is open, one speech recogniser listens for the name. It runs on **every** screen, not just Home, and stops when the app leaves the screen.
- After the name the app says **"Listening."**, pauses a beat so it does not hear itself, then keeps the microphone open for **8 seconds** before saying it did not catch anything.
- "Adanna, [request]" in one breath works too. The phrase **"special request"** also wakes her.
- The Home screen shows what the phone is hearing, live, with the orb rippling to the voice, so it is clear when it is listening.

### 3.3 Interrupt her at any time
- Say **"stop"**, **"quiet"**, **"enough"**, **"pause"** or her name, even while she is mid-sentence, and she stops.
- Only short phrases count, and only the name on its own, so her own sentences (which contain the name) never interrupt her.

### 3.4 Hands-free: "access granted"
- Say **"Adanna, access granted"** (or "take control", "hands free"). She keeps listening after every answer, so nothing needs touching and her name does not need repeating.
- Say **"that's all"** or **"goodbye"** to hand control back. Also available as a Settings switch.
- It does not auto-listen on the camera screens, where her spoken prompts would trigger it.

### 3.5 Tools
| Tool | What it does | Where it runs |
|---|---|---|
| **Mirror** | Guides you into frame by voice and haptics, counts down, takes the photo, describes how you look, flags fixable problems (stains, creases, mismatches, crooked headwrap). Follow-up questions about the photo. | AI on the server |
| **Read** | Point at a label, sign or paper and it reads the text aloud. | On the phone (no internet) |
| **Look around** | Describes surroundings or an object, hazards first, with positions ("on your left"). Follow-up questions work. | AI on the server |

- Read and Look around use the **full camera**, so you can take in everything the camera sees. A scanning line sweeps over the picture while it looks, and the whole thing takes about a second.
- The Mirror keeps its **oval mirror shape** because it is about your own reflection.
- On any camera screen: **tap anywhere** (a double-tap with TalkBack) to pause quietly and talk; **"pause"** or **"stop"** holds the scan or countdown; **"go on"** resumes; **"take the photo"**, **"flip the camera"**, **"look again"**, **"go back"** work by voice.

### 3.6 Questions about the world
- **Weather:** "Adanna, what's the weather in Abuja?" or just "will it rain?" (uses your approximate city). Today or tomorrow.
- **Facts and research:** "Who is…", "tell me about…", "what is the capital of…". Answered by the AI with a short Wikipedia extract as source.
- **Time and date** come from the phone itself.
- With a photo open, questions about the photo ("what is that stain on my shirt") stay with the photo; questions about the world go to the assistant.
- Limits: it cannot give breaking news, live scores or prices, and says so.

### 3.7 Change anything by voice
Brightness, volume, speed, text size, font, dark/light theme, high contrast, reduced motion, vibration, detail level, camera, language, voice, wake word, hands-free, and more.

Examples: "louder", "slower", "bigger text", "brighter", "dark mode", "high contrast on", "brief descriptions", "use the back camera", "speak Hausa", "change voice", "switch to Bola", "what can I say?".

"Make it more detailed" while a result is open describes the same photo again at that level.

---

## 4. Voice and language

- **Natural Nigerian voice:** a cloud voice (Azure neural, `en-NG`) for Adanna and Bola. If it is off, slow or offline, the phone's own voice takes over, so she is never silent.
- **Nigerian English writing:** the AI writes the way a Nigerian friend would speak, and names clothes correctly (agbada, buba, sokoto, iro and buba, gele, fila, ankara).
- **Languages:** Nigerian English, Hausa, Yoruba, Igbo, Ibibio. The AI writes English and the VoiceVerse API translates it. If translation is unavailable the user is told and hears English, so nothing fails silently.
- **Hearing the user:** uses the phone's offline speech model when there is one, and falls back to the normal online recogniser if the phone refuses those settings.

---

## 5. Reliability

- **Weak networks:** the app silently retries connection failures and gateway errors with a short back-off, retries slow requests visibly and kindly, and keeps a photo waiting when fully offline, describing it as soon as the connection returns.
- **Microphone:** one assistant turn owns the microphone from the name until the request is handled, so two recognisers never fight over it (the cause of earlier "I can't hear you" loops). Busy recognisers are retried, and half-heard sentences are kept instead of thrown away.
- **Errors are visible:** if speech recognition fails, the Home screen shows a plain sentence including the phone's error code.
- **Fast:** camera tools scan for about a second, short acknowledgements use the phone's own voice so there is no delay, and the capture mode favours speed.

---

## 6. Product and design

- **New Home screen:** a single living orb that *is* the assistant. It ripples while listening, pulses while speaking, shimmers while thinking. Tap it, or say her name. Four quiet buttons at the bottom (Mirror, Read, Look around, plus Settings) for touch.
- **New logo and icon:** a glowing mirror oval carrying the voice as three arcs, in brass and champagne on near-black. Applied to the app icon, splash screen, themed icon and the download page.
- **Download page:** serves the signed APK with version, size and a checksum so people can verify the file, and step-by-step help for Android's "unknown app" warnings.
- **Privacy:** photos are held only a few minutes so follow-up questions work, then deleted, and can be deleted instantly. Location and phone details are stripped from photos. The wake word listens only while the app is open and on screen.

---

## 7. Backend and API

All endpoints are under `/v1` and need a short-lived token (guest or account).

| Endpoint | Purpose |
|---|---|
| `POST /describe` | Describe a photo. `mode=mirror` (outfit, default) or `mode=scene` (surroundings). |
| `POST /ask` | Follow-up question about the current photo. |
| `POST /assist` | **New.** Weather and general questions. |
| `POST /speech` | **New.** Nigerian English audio (MP3). `voice=female` (Adanna) or `male` (Bola). |
| `/auth/*`, `/session/*`, `/health` | Guest tokens, accounts, session deletion, health. |

Also new: daily AI-call cap, per-device and per-IP rate limits on the new endpoints, and the translation hook for Hausa, Yoruba, Igbo and Ibibio.

**Server settings that switch features on** (set in the Vercel project for the API):

| Setting | Turns on |
|---|---|
| `AZURE_SPEECH_KEY` (+ `AZURE_SPEECH_REGION`, `AZURE_SPEECH_VOICE`, `AZURE_SPEECH_VOICE_MALE`) | The natural Nigerian voice, and a real male voice for Bola |
| `VOICEVERSE_URL` and `VOICEVERSE_API_KEY` | Translation into Hausa, Yoruba, Igbo, Ibibio |

---

## 8. Honest status

| Area | Status |
|---|---|
| Assistant logic, voice commands, settings, hands-free, routing | Built and covered by automated tests (about 120 Android tests, 205 backend tests passing) |
| Behaviour on a real phone (wake word, microphone, camera, voices) | **Needs on-device confirmation.** The tests cover the logic, not the phone's speech service. The microphone has been fixed repeatedly in response to real-phone feedback. |
| Natural Nigerian voice and Bola as a real male voice | Built. Needs `AZURE_SPEECH_KEY` on the server. Until then the phone's voice is used, and Bola is a lower-pitched version of it (Android does not say whether a voice is male or female). |
| Hausa, Yoruba, Igbo, Ibibio translation | Client built. Needs a VoiceVerse API key. The VoiceVerse service itself currently has no engine connected for Hausa, Yoruba and Igbo, and its Ibibio engine (USEM) was failing when last checked, so these may report "not translated" until that is fixed. |
| Weather and general questions | Live on the backend. Weather location is approximate (city level) unless a city is named. |
| Hands-free and the wake word on loudspeaker | The microphone also hears the assistant's voice. Earphones, or speaking a little louder, make interrupting more reliable. |
| Not done on purpose | Signing in or out and deleting an account by voice (destructive or needs typing, so they still need a touch). |

---

## 9. Voice cheat sheet

| Say | Result |
|---|---|
| "Adanna" / "Bola" / "special request" | Wake her |
| "Adanna, access granted" | Hands-free on |
| "That's all" / "goodbye" | Hands-free off |
| "Stop" / "pause" / "quiet" | Stop talking (and hold the camera scan) |
| "Go on" / "continue" | Resume |
| "Read this" | Open Read |
| "Look around" / "what colour is this" | Open Look around |
| "How do I look" / "mirror" | Open the Mirror |
| "Take the photo" / "flip the camera" / "look again" / "go back" | Act on the open screen |
| "What's the weather in Abuja?" | Weather |
| "Who is …?" / "tell me about …" | General question |
| "What time is it?" / "what's the date?" | From the phone |
| "Louder", "slower", "bigger text", "brighter", "dark mode", "speak Hausa", "change voice" | Change a setting |
| "Switch to Bola" / "switch to Adanna" | Change who talks |
| "What can I say?" | She lists what she can do |

---

## 10. Suggested next steps

1. Set the Azure and VoiceVerse keys on the server so the Nigerian voice, Bola, and the four languages are fully live.
2. Get VoiceVerse to connect translation engines for Hausa, Yoruba, Igbo and Ibibio (and repair the Ibibio engine).
3. Test with blind and low-vision users on several phones, especially the microphone, the wake word on loudspeaker, and TalkBack.
4. Add a naira note identifier (needs training data for the notes).
5. Add the final VisionMirror wordmark to the logo, and publish to the Play Store once testing is done.
