# VisionMirror AI: 2-minute demo script

**Story in one line:** a classmate asked a blind friend "How do I look?", and then asked, "How do I know for myself?" This app is the answer.

## Before you go on stage (5 minutes)

- Backend running with `VISION_PROVIDER=mock` for a guaranteed demo, or `anthropic` for the real thing. For mock, `MOCK_SCENARIO=stain` gives the best story (a mark near the collar). Check `<API_BASE_URL>/v1/health` returns `{"status":"ok"}` from the phone.
- Phone: media volume at ~70%, Do Not Disturb on, TalkBack **off** (the app speaks for itself; TalkBack would double up). Haptics on. Brightness high.
- Fresh install so onboarding plays (or Settings > Delete session data, and clear app data).
- Plug the phone into the projector or screen-mirror it so the audience can see the Halo.
- Have one person standing by as the "sighted helper" to describe what the audience sees (optional).
- Wi-Fi tested in the room. Fallback: hotspot from a second phone.

## Timeline

| Time | What you say / do | What the audience sees and hears |
|---|---|---|
| **0:00** | "A few weeks ago a classmate asked our friend, who is blind, *'How do I look?'* Then she asked the harder question: *'How do I know for myself?'*" | Nothing on screen yet. Let the question land. |
| **0:15** | "So we built a mirror that talks." Open the app (first launch). | Halo breathes in. The voice: *"Hi, I'm VisionMirror, a talking mirror…"* then the privacy line. The spoken words highlight in the caption. |
| **0:30** | Tap anywhere to continue. Allow camera, then microphone. | Each permission is explained aloud **before** the system dialog. Point out: *"She never has to find a tiny button."* |
| **0:45** | Hold the phone up, deliberately off-centre. | Voice: *"Move a little left."* Haptic ticks speed up as you correct. The Halo tightens and brightens; the oval edge glows. Say: *"Audio and touch are the interface. The glow is for the rest of us."* |
| **1:00** | Centre yourself. | Earcon, *"Perfect, hold still."*, countdown tones 3-2-1, shutter sound, auto-capture. Mention you can tap or press a volume key to capture, or tap to cancel. |
| **1:10** | While it analyses. | The Halo shimmers around the frozen photo, with a spoken *"One moment, I'm looking at you."* |
| **1:20** | Result arrives. | The description is **read aloud immediately** with the word highlight. Outfit rows and issue rows stagger in. Point to the issue: *"It tells her what it sees and what to do about it: icon, words and severity, never colour alone."* |
| **1:40** | Press and hold anywhere on the screen. Ask: *"Is that a stain on my collar?"* | Quartz Halo ripples with your voice; earcon on/off. The answer is spoken and shown. |
| **1:55** | Tap **Brief / Standard / Detailed** once, then **Retake**. | The indicator springs between segments. *"Her photo is never stored. The session is deleted the moment she retakes."* |
| **2:00** | Close: *"She asked how to know for herself. Now she can."* | Halo settles. |

## Lines worth saying

- "Audio and haptics are the interface. Motion is for everyone else, and nothing depends on seeing it."
- "Everything works with one hand and no precise tapping: the whole screen is the button."
- "Privacy: no accounts, a random device ID, and a photo kept for a few minutes only so she can ask follow-ups, then deleted."

## If something goes wrong

| Problem | Do this |
|---|---|
| No network | The app says it can't reach the internet and queues the photo in memory; say *"Guidance still works. It describes her as soon as it's back online"* and re-enable Wi-Fi. This is a feature demo in itself. |
| Face not found | The app tells you what to do. Say: *"It never leaves her guessing."* |
| Voice question not heard | It says *"I didn't catch that."* Try again, or use the Ask button. |
| Backend slow | The Halo shimmers and the app keeps reassuring. Fill with the privacy line. |

## Questions you may get

- **Does the AI see the photo on a server?** Yes: it goes to the vision model via our API, in memory only, never written to disk. A session lives in Redis for 10 minutes so follow-up questions work, then is deleted (immediately if she retakes).
- **How does it know where to stand?** On-device face detection (ML Kit). Nothing leaves the phone until the photo is taken.
- **Why dark?** Many low-vision users find bright screens painful; there is also a Light theme and a true High Contrast mode.
- **Languages?** The API takes a language code; the voice and speech recogniser follow the phone's language.
