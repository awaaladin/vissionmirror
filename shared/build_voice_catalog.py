"""The voice command catalog: every thing VisionMirror can do by voice, with the many ways people say it.

This file is the single source of truth. Running it writes `voice-commands.json`, which is
  - bundled into the Android app (the parser reads it, so what is documented is exactly what works),
  - rendered on the website's "What you can say" section and into docs/VOICE_COMMANDS.md.

    python shared/build_voice_catalog.py            # writes shared/voice-commands.json and checks for clashes

How a command matches: the sentence (lower case, no punctuation) must contain one of the command's `phrases`, as whole
words. If the command has `verbs`, a verb must be present too ("switch to" + "dark mode"), unless the sentence is short
(at most `bare` words), so "dark mode" alone works. Commands are tried in the order written here, first match wins.

`doc_only` commands are listed for people but handled by older, more specialised code (emergency, hands-free...).
"""

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def L(s: str) -> list[str]:
    """'a|b|c' -> ['a','b','c'] (one synonym per piece)."""
    return [p.strip() for p in s.replace("\n", "").split("|") if p.strip()]


# ---------------------------------------------------------------------------------------------------------------
# The small words that say "do this": any of them works in front of any command that takes a verb.
# ---------------------------------------------------------------------------------------------------------------
CHANGE = L("""switch to|change to|go to|set to|turn to|turn on|turn it to|put on|put it on|use|select|choose|pick|enable|activate|
make it|make the screen|make this|give me|i want|i need|i would like|i prefer|let me have|show me|please use|can you use|
can you switch to|swap to|move to|set it to|set the app to|go into|bring up|i want to use|i want to see|activate the|apply|set|switch|change|put|turn""")
OPEN = L("""open|go to|take me to|show me|show|switch to|bring up|start|launch|load|go into|enter|take me into|let me see|i want to see|
i want to use|i want|i need|can you open|please open|get me to|move to|navigate to|head to|bring me to|go back to|return to|back to|use""")
TURN_ON = L("""turn on|switch on|enable|activate|start|put on|set on|allow|i want|i need|use|give me|please turn on|please enable|
can you turn on|can you enable|bring on|go for|let me have|keep|set|make""")
TURN_OFF = L("""turn off|switch off|disable|deactivate|stop|end|remove|cancel|no more|get rid of|i dont want|i do not want|
please turn off|please disable|can you turn off|can you disable|shut off|kill|cut|drop|without|set off|no""")

SECTIONS = [
    {"id": "wake", "title": "Call me, stop me", "intro": "Say the assistant's name first, then your request. You can say the name and the request together: “Adanna, take a picture of me”."},
    {"id": "open", "title": "Open things", "intro": "Say where you want to go. These work from any screen."},
    {"id": "camera", "title": "Camera", "intro": "On the camera screens you can say these without touching the phone. Say “pause” at any time and I will stop and listen for your request."},
    {"id": "result", "title": "After I describe something", "intro": "I keep the photo for a few minutes so you can ask more about it."},
    {"id": "look", "title": "Screen and text", "intro": "Everything on the Settings screen can be changed by voice."},
    {"id": "sound", "title": "Sound and speaking", "intro": "How loud, how fast, and who talks to you."},
    {"id": "voice", "title": "Voice and language", "intro": "Adanna is a woman's voice, Bola is a man's. Both are the same assistant."},
    {"id": "safety", "title": "Safety and location", "intro": "Needs the phone's location. Texts your emergency contact only when you ask."},
    {"id": "handsfree", "title": "Hands-free", "intro": "For when you cannot touch the phone at all."},
]

C: list[dict] = []


def cmd(id, section, title, reply, phrases, verbs=(), bare=6, examples=(), doc_only=False, screens=(), note="", axis=None):
    C.append({
        "id": id, "section": section, "title": title, "reply": reply,
        "phrases": L(phrases) if isinstance(phrases, str) else list(phrases),
        "verbs": list(verbs), "bare": bare, "examples": list(examples),
        "doc_only": doc_only, "screens": list(screens), "note": note,
        "objects": L(axis[0]) if axis else [], "directions": L(axis[1]) if axis else [],
    })


# ============================================ safety first: things said in a hurry ============================================
cmd("safety.where", "safety", "Where am I", "", """where am i|what is my location|whats my location|my location|what street am i on|which place am i|
where are we|what place is this|tell me where i am|give me my location|find my location|locate me|where am i right now|
what is my address|whats my address|what area am i in|which area am i in|what town am i in|what city am i in|
which street is this|what road am i on|which road is this|my position|what is my position|where exactly am i|
am i lost|i am lost|im lost|help me find where i am|read my location|say my location|tell me my location|my gps|gps location""",
    examples=["Where am I?", "What street am I on?", "I'm lost"], doc_only=True)
cmd("safety.sos", "safety", "I need help", "", """i need help|help me|emergency|sos|send help|call for help|i am in danger|im in danger|i have been attacked|
i am being followed|im being followed|someone is following me|i am scared|im scared|i feel unsafe|i dont feel safe|
i am not safe|im not safe|i have been robbed|someone is attacking me|i was attacked|i am hurt|im hurt|i am injured|
i fell|i have fallen|i need an ambulance|i need the police|i need assistance now|urgent|urgent help|danger""",
    examples=["I need help", "Emergency", "I'm in danger"], doc_only=True, note="Counts down for a few seconds, then texts your location to your emergency contact. Say “cancel” to stop it.")
cmd("safety.share", "safety", "Send my location", "", """send my location|share my location|text my location|message my location|send my position|share my position|
text my location to|send my location to|share my location with|send location|share location|text location|
let them know where i am|tell my contact where i am|send my gps|share my gps|send where i am|text where i am|
message where i am|send my address|share my address|tell mum where i am|tell my family where i am""",
    examples=["Send my location to Mary", "Share my location"], doc_only=True)
cmd("safety.setcontact", "safety", "Set my emergency contact", "", """set my emergency contact to|add emergency contact|make an emergency contact|change my emergency contact to|
save my emergency contact|my emergency contact is|add an emergency contact|new emergency contact|set emergency contact|
save emergency contact|choose my emergency contact|pick my emergency contact|update my emergency contact|
set up emergency contact|register my emergency contact|put in my emergency contact|my emergency contact will be|
i want to set my emergency contact|i want to add an emergency contact|set a contact for emergencies|
set someone for emergencies|add someone for emergencies""",
    examples=["Set my emergency contact to Mary", "My emergency contact is Tunde"], doc_only=True, note="You can also type it: Settings, Emergency contact.")
cmd("safety.list", "safety", "Who is my emergency contact", "", "who is my emergency contact|who are my emergency contacts|read my emergency contacts|list my emergency contacts",
    examples=["Who is my emergency contact?"], doc_only=True)
cmd("safety.clear", "safety", "Remove my emergency contact", "", "remove my emergency contact|delete my emergency contact|clear my emergency contacts|remove emergency contacts",
    examples=["Remove my emergency contact"], doc_only=True)
cmd("safety.call", "safety", "Call the emergency number", "", "call 112|dial 112|call 199|call the police|call emergency|call an ambulance|call the emergency number",
    examples=["Call 112", "Call the police"], doc_only=True, note="Opens the dialer; you press the green button.")

# ============================================ sound ============================================
cmd("vol.max", "sound", "Volume to the maximum", "", """max volume|full volume|loudest|maximum volume|volume to the max|volume to max|highest volume|as loud as possible|
all the way up|volume full|volume maximum|turn it all the way up|blast it|loudest possible|volume at maximum|set volume to maximum|
set the volume to max|maximum sound|full sound|highest sound level|max sound|full blast|volume all the way up|volume as high as it goes""",
    examples=["Maximum volume", "Full volume"])
cmd("vol.up", "sound", "Louder", "", """louder|volume up|turn it up|speak up|increase volume|increase the volume|raise the volume|raise volume|more volume|higher volume|
make it louder|make louder|louder please|up the volume|boost the volume|boost volume|crank it up|i cannot hear you|cant hear you|
i cant hear you|too quiet|too soft|too low|you are too quiet|speak louder|talk louder|say it louder|increase sound|sound up|
turn up the sound|volume higher|more sound|amplify|bump the volume|a bit louder|little louder|much louder|volume a bit higher""",
    examples=["Louder", "Turn it up", "I can't hear you"])
cmd("vol.down", "sound", "Quieter", "", """quieter|volume down|turn it down|softer|lower the volume|lower volume|decrease volume|decrease the volume|reduce volume|
reduce the volume|less volume|lower the sound|make it quieter|make quieter|quieter please|speak softer|talk softer|too loud|too noisy|
you are too loud|sound down|turn down the sound|volume lower|less sound|bring the volume down|tone it down|lower your voice|
a bit quieter|little quieter|much quieter|volume a bit lower|not so loud|not too loud|keep it down""",
    examples=["Quieter", "Turn it down", "You're too loud"])
cmd("rate.normal", "sound", "Normal speaking speed", "", """normal speed|normal pace|speak normally|default speed|normal speech|speed normal|speed to normal|reset speed|
reset your speed|back to normal speed|regular speed|usual speed|standard speed|medium speed|average speed|normal talking speed|
talk normally|speak at normal speed|speed default|default pace|your usual speed|speed back to normal|original speed|speak at your usual speed""",
    examples=["Normal speed", "Speak normally"])
cmd("rate.slow", "sound", "Speak slower", "", """slower|slow down|speak slowly|talk slowly|speed down|too fast|you are too fast|you talk too fast|you speak too fast|
speak more slowly|talk more slowly|more slowly|slowly please|slow it down|slow speech|slower speech|reduce speed|reduce your speed|
lower the speed|lower speed|decrease speed|decrease your speed|take it slow|take your time|not so fast|not too fast|go slower|
say it slower|speak slower|talk slower|a bit slower|little slower""",
    examples=["Slower", "You're too fast", "Slow down"])
cmd("rate.fast", "sound", "Speak faster", "", """faster|speed up|speak quickly|talk faster|too slow|you are too slow|you talk too slow|you speak too slowly|speak faster|
talk quicker|quicker|more quickly|hurry up|faster please|go faster|say it faster|increase speed|increase your speed|raise the speed|
higher speed|speed it up|fast speech|faster speech|speak fast|talk fast|a bit faster|little faster|move faster|get on with it|much faster""",
    examples=["Faster", "Speed up", "You're too slow"])

# ============================================ screen: brightness, text, theme ============================================
cmd("bright.full", "look", "Full brightness", "", """full brightness|max brightness|maximum brightness|brightest|brightness to the max|brightness to max|highest brightness|
brightness full|brightness maximum|as bright as possible|all the way bright|set brightness to maximum|set the brightness to max|
maximum light|full light|brightness at maximum|brightness 100|brightness one hundred|full screen brightness|brightness all the way up""",
    examples=["Full brightness", "Maximum brightness"])
cmd("bright.auto", "look", "Brightness like the phone", "", """auto brightness|automatic brightness|normal brightness|reset brightness|default brightness|brightness auto|brightness automatic|
brightness normal|brightness default|follow phone brightness|phone brightness|system brightness|brightness like the phone|
brightness back to normal|brightness back to default|restore brightness|brightness reset|adaptive brightness|let the phone decide brightness|
use phone brightness|use the phones brightness|brightness to auto|set brightness to auto|adjust brightness automatically|automatic screen brightness""",
    examples=["Automatic brightness", "Reset the brightness"])
cmd("bright.up", "look", "Brighter screen", "", """brighter|brightness up|increase brightness|increase the brightness|raise brightness|raise the brightness|more brightness|
higher brightness|make it brighter|make the screen brighter|brighten|brighten the screen|lighten the screen|screen brighter|
light up the screen|turn up the brightness|turn the brightness up|up the brightness|boost brightness|screen too dark|it is too dark|
its too dark|too dark|cant see the screen|i cant see the screen|i cannot see the screen|more light on the screen|brighter screen|
make the screen lighter|screen lighter""",
    examples=["Brighter", "Increase the brightness", "I can't see the screen"])
cmd("bright.down", "look", "Dimmer screen", "", """dimmer|brightness down|decrease brightness|decrease the brightness|lower brightness|lower the brightness|less brightness|
reduce brightness|reduce the brightness|dim the screen|dim it|dim|make it dimmer|make the screen dimmer|screen dimmer|dim the display|
turn down the brightness|turn the brightness down|too bright|screen too bright|it hurts my eyes|its too bright|it is too bright|
glare|softer screen|less glare|lower the light|tone down the screen|brightness lower|dimmer screen|make the screen softer""",
    examples=["Dimmer", "Dim the screen", "It's too bright"])

cmd("text.huge", "look", "Largest text", "", """huge text|biggest text|largest text|maximum text|maximum font|max text|max font size|maximum font size|biggest font|largest font|
text to the max|text as big as possible|as big as possible|extra large text|extra big text|giant text|enormous text|very big text|
very large text|really big text|really large text|text size huge|text size maximum|text size max|set text to huge|biggest letters|
largest letters|biggest print|biggest writing|largest writing""",
    examples=["Huge text", "Biggest text"])
cmd("text.normal", "look", "Normal text size", "", """normal text|normal text size|reset text|default text size|normal font size|default font size|reset the text size|reset text size|
text size normal|text size default|text size back to normal|text back to normal|regular text|regular text size|standard text size|
medium text|medium text size|average text size|usual text size|original text size|default text|text to normal|set text to normal|
reset the font size|reset font size|font size normal|font size default""",
    examples=["Normal text size", "Reset the text size"])
cmd("text.bigger", "look", "Bigger text", "", """bigger text|larger text|increase text|increase the text|text bigger|text larger|make the text bigger|make it bigger|bigger font|
larger font|increase font|increase the font|increase font size|increase the font size|font bigger|font larger|make the font bigger|
enlarge the text|enlarge text|enlarge the font|zoom in|zoom the text|magnify|magnify the text|big letters|bigger letters|larger letters|
bigger writing|make the letters bigger|make writing bigger|bigger print|larger print|big print|make everything bigger|bigger words|
i cant read this|i cannot read this|too small|text too small|text is too small|words too small|font too small|letters too small|
cant read the text|i cannot read the text|text is tiny|tiny text""",
    examples=["Bigger text", "Increase the font size", "I can't read this"])
cmd("text.smaller", "look", "Smaller text", "", """smaller text|reduce text|reduce the text|text smaller|make the text smaller|make it smaller|smaller font|reduce font|reduce the font|
decrease text|decrease the text|decrease font size|decrease the font size|font smaller|shrink the text|shrink text|shrink the font|
make the font smaller|smaller letters|smaller writing|smaller print|make the letters smaller|make everything smaller|text too big|
text is too big|font too big|letters too big|words too big|too big|too large|text too large|text is too large""",
    examples=["Smaller text", "Reduce the font size", "The text is too big"])

cmd("font.hyper", "look", "Easy-to-read font", "", """dyslexia|dyslexia font|dyslexic font|hyperlegible|hyperlegible font|easy to read font|easy font|easier font|clear font|clearer font|
readable font|more readable font|accessible font|atkinson|atkinson font|special font|reading font|font for dyslexia|font i can read|
easy reading font|friendly font|simple clear font|legible font""",
    examples=["Easy to read font", "Dyslexia font"], verbs=CHANGE, bare=4)
cmd("font.standard", "look", "Standard font", "", """standard font|normal font|system font|plain font|default font|regular font|phone font|usual font|original font|
ordinary font|basic font|usual lettering|normal lettering|normal letters|font back to normal|default letters""",
    examples=["Standard font", "Normal font"], verbs=CHANGE, bare=4)

cmd("contrast.off", "look", "Normal colours", "High contrast is off.", """contrast off|no contrast|normal contrast|low contrast|turn off contrast|disable contrast|standard contrast|regular contrast|
less contrast|reduce contrast|remove contrast|switch off contrast|turn off high contrast|disable high contrast|normal colours|
normal colors|default colours|default colors|standard colours|original colours|regular colours|usual colours|
go back to normal colours|no high contrast|high contrast off|stop high contrast""",
    examples=["Turn off high contrast", "Normal colours"])
cmd("contrast.on", "look", "High contrast", "High contrast is on.", """high contrast|contrast|strong contrast|contrast mode|max contrast|maximum contrast|stronger colours|bolder colours|clear colours|
black and white|black and yellow|contrasting colours|sharper colours|more contrast|increase contrast|boost contrast|high contrast mode|
high contrast theme|accessibility colours|easier colours|bold colours|strong colours|vivid colours|clearer colours|high contrast on""",
    examples=["High contrast", "Increase the contrast", "Stronger colours"], verbs=CHANGE + TURN_ON, bare=3)

cmd("theme.light", "look", "Light mode", "Light mode.", """light mode|light theme|bright mode|white mode|white theme|white screen|white background|light colours|light colors|
day mode|daytime mode|day theme|daylight mode|sunny mode|lighter mode|lighter theme|light background|bright theme|bright background|
paper mode|clear mode|morning mode|lighten the theme|light please""",
    examples=["Light mode", "Switch to the white theme", "Day mode"], verbs=CHANGE, bare=3)
cmd("theme.dark", "look", "Dark mode", "Dark mode.", """dark mode|dark theme|night mode|night theme|dark screen|black screen|black theme|black mode|darker mode|dark colours|dark colors|
dark background|go dark|make it dark|make it darker|darker|darker theme|dim mode|low light mode|evening mode|amoled mode|midnight mode|
black background|dark please|night time mode|nighttime mode|darkness|dark side|shadow mode|dark appearance|eye saver mode""",
    examples=["Dark mode", "Make it darker", "Change to the black theme", "Night mode"], verbs=CHANGE, bare=3)
cmd("theme.system", "look", "Theme like the phone", "The theme now follows your phone.", """system theme|automatic theme|auto theme|follow my phone theme|phone theme|theme like my phone|follow the phone|follow the system theme|
default theme|normal theme|standard theme|theme auto|theme automatic|theme default|theme system|same as my phone|match my phone|
match the phone|let the phone decide the theme|use the phones theme|use phone theme|original theme|usual theme""",
    examples=["Automatic theme", "Follow my phone's theme"], verbs=CHANGE, bare=3)

cmd("motion.full", "look", "Full motion", "Full motion is back.", """full motion|motion on|normal motion|animations on|turn on animations|turn on motion|enable motion|enable animations|
more motion|restore motion|bring back motion|movement on|moving screen|animate|show animations|turn animations on|allow motion|
allow animations|normal animations|default motion|standard motion|motion back""",
    examples=["Full motion", "Animations on"])
cmd("motion.reduce", "look", "Reduce motion", "Reduced motion is on.", """reduce motion|less motion|calm screen|calmer screen|reduced motion|no motion|motion off|animations off|turn off animations|
turn off motion|disable motion|disable animations|stop animations|stop the motion|stop moving|still screen|steady screen|
no animations|less animation|fewer animations|less movement|no movement|movement off|stop the movement|calm mode|quiet screen|
keep the screen still|stop the screen moving|screen too busy""",
    examples=["Reduce motion", "Calm screen", "Turn off animations"])

cmd("haptics.off", "look", "Vibration off", "Vibration is off.", """vibration off|no vibration|stop vibrating|vibrate off|haptics off|haptic off|no haptics|turn off vibration|turn off haptics|
disable vibration|disable haptics|switch off vibration|switch off haptics|stop the vibration|stop the buzzing|no buzzing|
buzzing off|buzz off|turn off the buzz|do not vibrate|dont vibrate|no more vibration|no more vibrating|silence the vibration|
vibration disabled|haptic feedback off|turn off haptic feedback|disable haptic feedback|no haptic feedback""",
    examples=["Vibration off", "Stop vibrating"])
cmd("haptics.on", "look", "Vibration on", "Vibration is on.", """vibration|vibrate|haptics|haptic|vibration on|haptics on|turn on vibration|turn on haptics|enable vibration|enable haptics|
vibration please|vibrate please|buzz|buzzing|buzzing on|haptic feedback|haptic feedback on|turn on haptic feedback|
enable haptic feedback|switch on vibration|switch on haptics|use vibration|use haptics|feel the buzz|let it vibrate|allow vibration|
allow haptics|bring back vibration|bring back the vibration""",
    examples=["Vibration on", "Turn on haptics"], verbs=TURN_ON, bare=3)

cmd("detail.brief", "look", "Short descriptions", "Brief descriptions.", """brief description|brief descriptions|short description|short descriptions|less detail|shorter descriptions|keep it short|
be brief|be short|brief|keep it brief|short and sweet|just the basics|basics only|only the basics|shorter|shorter answers|
short answers|brief answers|quick description|quick descriptions|few words|fewer words|say less|talk less|less words|less talking|
summary only|just a summary|only a summary|in brief|in short|tl dr|make it shorter|make descriptions shorter|tell me less""",
    examples=["Brief descriptions", "Keep it short", "Say less"])
cmd("detail.detailed", "look", "Detailed descriptions", "Detailed descriptions.", """detailed description|detailed descriptions|long description|more detail|describe everything|longer descriptions|
be detailed|in detail|full detail|full details|all the details|every detail|tell me everything|tell me more detail|more details|
more information|more info|longer answers|detailed answers|give me detail|give me the details|explain in detail|go into detail|
deep description|thorough description|describe it fully|full description|complete description|say more|talk more|more words|
make it longer|make descriptions longer|tell me more about it|be thorough|be more detailed""",
    examples=["Detailed descriptions", "Tell me everything", "More detail"])
cmd("detail.standard", "look", "Normal descriptions", "Standard descriptions.", """standard description|normal detail|normal description|standard descriptions|normal descriptions|medium detail|medium description|
default detail|default description|regular detail|regular description|usual detail|balanced description|balanced detail|
average detail|not too long|not too short|in between|middle detail|detail normal|detail default|detail standard|reset detail|
normal level of detail|standard level of detail|normal length|default length|usual length""",
    examples=["Normal descriptions", "Standard detail"])

# ============================================ listening behaviour ============================================
cmd("wake.off", "wake", "Stop listening for my name", "I won't listen for my name.", """stop listening for my name|dont listen for my name|do not listen for my name|wake word off|turn off wake word|disable wake word|
no wake word|stop listening for the wake word|stop waiting for my name|stop waiting for the name|turn off listening for my name|
turn off the name|disable listening|stop always listening|stop listening in the background|stop background listening|
dont listen in the background|no background listening|only listen when i tap|listen only when i tap|listen only when i press""",
    examples=["Stop listening for my name", "Wake word off"])
cmd("wake.on", "wake", "Listen for my name", "I'll listen for my name.", """wake word|wake word on|turn on wake word|enable wake word|listen for my name|listening for my name|listen for the name|
start listening for my name|listen for adanna|listen for bola|always listen|keep listening for my name|call me by name|
turn on listening for my name|enable listening for my name|wait for my name|wait for the name|respond to my name|answer to my name|
come when i call|come when i call you|listen in the background|background listening|answer when i call""",
    examples=["Listen for my name", "Wake word on"])
cmd("commands.off", "wake", "Voice commands off", "Voice commands are off.", """voice commands off|turn off voice commands|disable voice commands|no voice commands|stop voice commands|stop taking commands|
turn off commands|disable commands|commands off|no commands|dont take commands|do not take commands|stop obeying|stop listening to commands""",
    examples=["Voice commands off"])
cmd("commands.on", "wake", "Voice commands on", "Voice commands are on.", """voice commands|voice commands on|turn on voice commands|enable voice commands|start voice commands|take commands|obey me|
commands on|turn on commands|enable commands|listen to commands|listen for commands|start taking commands|do what i say|follow my commands""",
    examples=["Voice commands on"], verbs=TURN_ON, bare=3)
cmd("barge.off", "wake", "Don't stop when I speak", "I won't stop when you speak.", """dont stop when i speak|do not stop when i speak|keep talking when i speak|dont interrupt yourself|do not interrupt yourself|
stop barge in|turn off barge in|barge in off|no barge in|dont stop talking when i talk|ignore me while you talk|dont let me interrupt|
do not let me interrupt|let me not interrupt|dont stop for my voice|keep going when i talk|dont stop for my name|
do not stop for my name|dont stop for stop|do not stop for stop|disable interrupting|disable interruption""",
    examples=["Don't stop when I speak"])
cmd("barge.on", "wake", "Stop when I speak", "I'll stop when you say stop.", """stop when i speak|stop when i say stop|stop when i talk|stop talking when i talk|barge in|barge in on|turn on barge in|
let me interrupt|let me interrupt you|allow interrupting|allow interruption|enable interrupting|enable interruption|
stop when i say your name|stop for my name|stop for my voice|stop if i speak|listen while you talk|listen while you speak|
hear me while you talk|hear me while you speak|interrupt yourself|be interruptible|let me cut in|let me cut you off""",
    examples=["Let me interrupt you", "Stop when I say stop"])

# ============================================ voice: who talks ============================================
cmd("voice.bola", "voice", "Switch to Bola (man's voice)", "Hello, I'm Bola.", """bola|bola voice|male voice|mans voice|man voice|a man|the man|speak as bola|be bola|talk to bola|put bola on|let me talk to bola|
give me bola|i want bola|bring bola|call bola|get bola|go to bola|bola please|deeper voice|lower voice|boy voice|gentleman|
masculine voice|man speaking|male assistant|the male|the guy|the man voice|voice of a man|voice of bola""",
    examples=["Switch to Bola", "Use the man's voice", "Let me talk to Bola"], verbs=CHANGE + ["talk to", "speak to", "call", "get", "bring", "be", "speak as"], bare=3)
cmd("voice.adanna", "voice", "Switch to Adanna (woman's voice)", "Hello, I'm Adanna.", """adanna|adanna voice|female voice|womans voice|woman voice|a woman|the woman|speak as adanna|be adanna|talk to adanna|put adanna on|
let me talk to adanna|give me adanna|i want adanna|bring adanna|call adanna|get adanna|go back to adanna|go to adanna|adanna please|
lady voice|girl voice|lady|feminine voice|woman speaking|female assistant|the female|the lady|the girl|the woman voice|
voice of a woman|voice of adanna|higher voice""",
    examples=["Switch to Adanna", "Use the woman's voice", "Go back to Adanna"], verbs=CHANGE + ["talk to", "speak to", "call", "get", "bring", "be", "speak as", "go back to"], bare=3)
cmd("voice.next", "voice", "Change the voice", "", """change voice|different voice|next voice|another voice|change your voice|new voice|other voice|try another voice|try a different voice|
switch voice|switch your voice|swap voice|swap your voice|different sounding voice|change how you sound|change the way you sound|
something different|i dont like this voice|i do not like this voice|another one|use another voice|use a different voice|
pick another voice|choose another voice|cycle voices|next one please""",
    examples=["Change your voice", "Try another voice"])
cmd("voice.local", "voice", "Use the phone's own voice", "I'll use your phone's voice.", """phone voice|use your phones voice|use the phones voice|phones voice|offline voice|local voice|robot voice|the phones own voice|
cloud voice off|natural voice off|nigerian voice off|online voice off|turn off cloud voice|turn off natural voice|stop using the cloud voice|
dont use the internet voice|do not use the internet voice|no internet voice|use the offline voice|use offline voice|
use the local voice|use the built in voice|built in voice|device voice|system voice|default voice|normal phone voice""",
    examples=["Use your phone's voice", "Cloud voice off"])
cmd("voice.cloud", "voice", "Use the natural Nigerian voice", "I'll use the natural Nigerian voice.", """natural voice|nigerian voice|online voice|cloud voice|natural nigerian voice|real voice|human voice|better voice|nicer voice|
cloud voice on|natural voice on|nigerian voice on|online voice on|turn on cloud voice|turn on natural voice|use the cloud voice|
use the natural voice|use the nigerian voice|use the online voice|use the internet voice|internet voice|naija voice|
nigerian accent|nigerian english voice|use nigerian accent|speak like a nigerian|speak naturally""",
    examples=["Natural voice", "Use the Nigerian voice"], verbs=CHANGE + TURN_ON, bare=3)

# ============================================ open things ============================================
cmd("open.back", "open", "Go back", "", """go back|previous screen|close this|exit|back|previous|last screen|close|leave|get out|cancel this|go away|dismiss|return|undo|
take me back|step back|go to the previous screen|back one screen|quit this|leave this screen|exit this screen|close this screen|
get me out of here|go back please|back please|previous page|go to the last screen|back to where i was|return to the last screen|
take me to the previous screen|take me to the last screen|i want to go back|i want to leave|let me out""",
    examples=["Go back", "Close this", "Take me back"], bare=6)
cmd("open.settings", "open", "Settings", "", """settings|setting|preferences|options|configuration|config|setup|set up|app settings|control panel|adjustments|customise|customize|
personalise|personalize|change settings|change preferences|tweak|controls|my preferences|app options|system settings|
the settings|the options|the preferences|setting page|settings page|settings screen|settings menu|options menu|
preferences page|preferences screen|where i change things|where i set things|configure|configure the app|app setup""",
    examples=["Open settings", "Take me to the options", "Preferences"], verbs=OPEN, bare=3)
cmd("open.account", "open", "Sign in or out", "", """sign in|log in|login|sign up|register|create account|create an account|my account|account|profile|sign out|log out|logout|sign off|
switch account|change account|account settings|my profile|my details|log into my account|sign into my account|i want to log in|
i want to sign in|i want to sign out|i want to log out|make an account|open an account|new account|join|become a member|
my login|my sign in|log me in|sign me in|log me out|sign me out""",
    examples=["Sign in", "My account", "Log out"], verbs=OPEN, bare=3)
cmd("open.home", "open", "Home", "", """home|home screen|main screen|main menu|menu|start screen|beginning|the start|front page|dashboard|start page|home page|go home|take me home|
back to the start|main page|homepage|first screen|launcher|where i started|main|the home screen|the main menu|the home page|
the main screen|the start screen|go to the start|go to the beginning|return home|back home|home please|go to home|
take me to the start|take me to the beginning|take me to the main menu""",
    examples=["Go home", "Main menu", "Back to the start"], verbs=OPEN, bare=3)
cmd("open.guide", "open", "Guide me (walk safely)", "", """guide me|guide mode|start guide|start guiding|guide|walk with me|walking mode|watch ahead|watch for obstacles|detect objects|
obstacle detection|detect obstacles|obstacles|what is ahead of me|what is ahead|help me walk|help me navigate|navigate|navigation|
navigate for me|avoid obstacles|watch my path|check my path|path ahead|scan ahead|lead me|show me the way|walking assistant|walking help|
safe walk|mobility mode|cane mode|guide me forward|guide me along|guide me as i walk|watch where i walk|watch where i am going|
keep me safe|keep me safe while i walk|check for obstacles|look for obstacles|obstacle mode|start walking mode|walking guide""",
    examples=["Guide me", "Walk with me", "Watch for obstacles"], bare=5)
cmd("open.look", "open", "Look around me", "", """look around|look around me|around me|what is around me|whats around me|in front of me|what is in front of me|whats in front of me|
surroundings|check my surroundings|check surroundings|my surroundings|what is here|whats here|describe|describe this|describe what you see|
describe the scene|scene|what do you see|what can you see|what am i holding|what colour is this|what color is this|what colour is that|
what color is that|look at this|environment|check the room|describe the room|look|have a look|take a look|what is this room|
what is there|what is nearby|whats nearby|what is near me|whats near me|describe my surroundings|describe my environment|see around me|
tell me what is around me|tell me what is in front of me|what is in the room|who is here|who is around me|who is near me""",
    examples=["Look around", "What's around me?", "Check my surroundings", "Describe the room"], bare=7)
cmd("open.read", "open", "Read what is in front of me", "", """read|reading|reader|reading mode|read mode|read this|read that|read it|read for me|read to me|read the text|read text|
what does it say|what does this say|what does that say|what is written|whats written|read the label|read the sign|read the page|
read the document|read the letter|read the menu|text reader|scan text|scan the text|ocr|read the packet|read the box|read the bottle|
read the tin|read the can|read the note|read the paper|read the card|read the book|read the screen|read the message|text|label|
what is the label|what does the label say|what does the sign say|what does the box say|what is on the label|read what is here|
read whats in front of me|read what is in front of me|read out the text|read it out|read it out loud|read aloud|help me read""",
    examples=["Read this", "What does it say?", "Read the label", "Open the reader"], bare=7)
cmd("open.mirror", "open", "Mirror (how do I look)", "", """mirror|photo|selfie|outfit|how do i look|picture|how i look|check my outfit|check my look|what am i wearing|what i am wearing|
dress check|clothes check|check my clothes|front camera mode|check me|see me|look at me|appearance|check my appearance|
check my face|my reflection|reflection|check how i look|am i dressed well|the mirror|my outfit|my look|my clothes|my appearance|
check my hair|how is my hair|how is my outfit|is my outfit okay|is my outfit ok|do my clothes match|do my clothes go together|
am i well dressed|check if i look good|check if my clothes match|use the mirror|open the mirror|mirror mode|selfie mode""",
    examples=["Open the mirror", "How do I look?", "Check my outfit"], bare=7)

# ============================================ camera ============================================
cmd("cam.selfie", "camera", "Take a picture of me", "", """take a picture of me|take a photo of me|take my picture|take my photo|take me a picture|take me a photo|take a selfie|take selfie|
take a pic of me|picture of me|photo of me|snap me|capture me|take a picture of myself|take a photo of myself|take my pic|
take a pic of myself|snap a picture of me|snap a photo of me|snap my picture|snap my photo|get a picture of me|get a photo of me|
take a snap of me|take a snapshot of me|shoot me|click my picture|click my photo|click a picture of me|click a photo of me|
make a picture of me|make a photo of me|i want a photo of me|i want a picture of me""",
    examples=["Take a picture of me", "Take my photo", "Take a selfie"], bare=12)
cmd("cam.front", "camera", "Use the front camera", "", """front camera|selfie camera|front facing camera|front-facing camera|camera facing me|camera that faces me|camera on the front|
face camera|user camera|self camera|inward camera|screen camera|the camera facing me|front lens|front side camera|front cam|
selfie cam|front side|the front one|front facing|selfie lens|front view""",
    examples=["Switch to the front camera", "Use the selfie camera"], verbs=CHANGE + ["open", "go to"], bare=3)
cmd("cam.back", "camera", "Use the back camera", "", """back camera|rear camera|main camera|camera facing away|outer camera|outward camera|world camera|back lens|back cam|rear lens|
rear cam|camera on the back|primary camera|the main lens|environment camera|normal camera|regular camera|camera facing out|
camera facing forward|back side camera|rear facing camera|rear-facing camera|the back one|rear facing|back facing|back view""",
    examples=["Switch to the back camera", "Use the rear camera"], verbs=CHANGE + ["open", "go to"], bare=3)
cmd("cam.flip", "camera", "Flip the camera", "", """flip the camera|flip camera|switch camera|switch the camera|other camera|change camera|change the camera|swap camera|swap the camera|
rotate camera|turn the camera around|turn camera around|reverse camera|the other camera|different camera|toggle camera|camera flip|
camera switch|flip|use the other camera|other side|other lens|swap lens|change lens|switch lens|opposite camera|flip it|
flip the phone camera|flip the lens|swap the lens|change the lens|switch the lens|use the other lens|use the other side|
turn the camera over|turn it around|turn around the camera|camera around|other way round camera|swap cameras|switch cameras""",
    examples=["Flip the camera", "Switch camera", "Use the other camera"], bare=6)
cmd("cam.snapnow", "camera", "Take a photo", "", """take the photo|take the picture|take a photo|take photo|take a picture|take picture|capture|snap|snap it|click|shoot|
take it now|press the button|press capture|photo now|picture now|okay take it|shoot it|capture now|take a shot|take shot|take the shot|
take it please|please take it|please take the photo|please take the picture|get the shot|get the photo|get the picture|grab the photo|
grab the picture|snap the photo|snap a photo|snap a picture|snap the picture|shoot the photo|shoot the picture|go ahead and take it|
take the photo now|take the picture now""",
    examples=["Take the photo", "Take a picture", "Snap it"], bare=9,
    note="On the mirror it takes the photo at once. From any other screen it opens the mirror and takes it.")
cmd("cam.snap", "camera", "Take the photo now (on the mirror)", "", """take the photo|take the picture|take a photo|take photo|take a picture|take picture|capture|snap|snap it|click|shoot|now|ready|
take it|take it now|go|do it|press the button|press capture|photo now|picture now|i am ready|im ready|okay take it|shoot it|
capture now|fire|cheese|smile|take a shot|take shot|take the shot|take it please|please take it|please take the photo|
please take the picture|get the shot|get the photo|get the picture|grab the photo|grab the picture|snap the photo|snap a photo|
snap a picture|snap the picture|shoot the photo|shoot the picture|go ahead and take it|take the photo now|take the picture now|
photo|picture|now please|go now|ready now|take it now please""",
    examples=["Take the photo", "Snap it", "I'm ready"], screens=["mirror"], bare=6)
cmd("cam.again", "camera", "Look again", "", """look again|try again|scan again|once more|retry|repeat the scan|do it again|again|scan once more|one more time|another look|another scan|
redo|refresh|rescan|re scan|look once more|check again|read again|read it again|look one more time|take another look|scan another|
update|do that again|start over|start again|new scan|scan it again|look at it again|read this again|read that again|have another look|
have another go|try once more|try one more time|give it another go|give it another try|scan one more time|repeat|one more|
go again|run it again|run the scan again|scan afresh|look afresh|fresh scan|fresh look""",
    examples=["Look again", "Scan again", "Try again"], screens=["look"], bare=6)
cmd("cam.pause", "camera", "Pause (then speak your request)", "", """pause|hold on|wait|stop|hold it|hold|freeze|stand by|give me a moment|give me a second|one second|one moment|just a moment|
hang on|hold up|not yet|wait a moment|wait a second|stop scanning|pause scanning|pause the scan|pause guidance|stop guiding|
stop for a moment|pause for a bit|let me think|i need a moment|time out|wait please|please wait|stay|halt|freeze please|quiet|enough|silence|hush|cancel""",
    examples=["Pause", "Hold on", "Wait"], doc_only=True, note="Works even while I am talking. After you say it I stop, and listen for what you need.")
cmd("cam.resume", "camera", "Carry on", "", """go on|continue|carry on|resume|keep going|go ahead|proceed|start again|proceed please|okay go|go|ready|im ready|i am ready|
let us continue|lets continue|continue please|go on please|unpause|un pause|play|start|restart|go ahead please|resume scanning|
resume guiding|carry on please|you can continue|you may continue|keep scanning""",
    examples=["Go on", "Continue", "Carry on"], doc_only=True)

# ============================================ after a description ============================================
cmd("res.retake", "result", "Take another photo", "", """retake|take another|try again|new photo|redo|take another photo|take another picture|take it again|try another photo|
another photo|another picture|redo the photo|new picture|take a new photo|take a new picture|do the photo again|photo again|
picture again|again with the camera|start the photo again|new selfie|another selfie|take another selfie|retake the photo|
retake the picture|retake it|take that again|let me retake|i want to retake|i want another photo|i want a new photo""",
    examples=["Retake", "Take another photo"], doc_only=True)
cmd("res.repeat", "result", "Say that again", "", """repeat|say that again|say again|read again|repeat that|say it again|what did you say|come again|pardon|sorry what|once more please|
read that again|tell me again|go over that again|again please|repeat the description|i did not hear|didnt catch that|can you repeat|
tell me that again|say that once more|what was that|i missed that|i didnt hear that|say it once more|one more time please|
repeat it please|repeat please|repeat yourself|can you say that again|could you repeat that|read it back|read it back to me""",
    examples=["Repeat", "Say that again", "I missed that"], doc_only=True)
cmd("res.ask", "result", "Ask a question", "", """ask|question|ask a question|i have a question|ask you something|can i ask|let me ask|i want to ask|i have a follow up|follow up|
one more question|another question|can i ask something|ask something|i need to ask|may i ask|i would like to ask|
i would like to know|i want to know|can you tell me|could you tell me|tell me|question please|a question|quick question|
i got a question|i have another question|can i ask another question""",
    examples=["Ask a question", "I have a question", "Can I ask something?"], doc_only=True)

# ============================================ language ============================================
cmd("lang.set", "voice", "Change the language I speak to you in", "", """english|nigerian english|hausa|yoruba|igbo|ibibio|speak english|speak hausa|speak yoruba|speak igbo|speak ibibio|talk in english|
talk in hausa|talk in yoruba|talk in igbo|talk in ibibio|reply in hausa|reply in yoruba|reply in igbo|answer in hausa|answer in yoruba|
answer in igbo|respond in hausa|respond in yoruba|respond in igbo|use hausa|use yoruba|use igbo|use english|switch to hausa|
switch to yoruba|switch to igbo|switch to english|change language to hausa|change language to yoruba|change language to igbo|
change language to english|translate to hausa|translate to yoruba|translate to igbo|say it in hausa|say it in yoruba|say it in igbo|
tell me in hausa|tell me in yoruba|tell me in igbo|in hausa please|in yoruba please|in igbo please|in english please""",
    examples=["Speak Hausa", "Reply in Yoruba", "Switch to Igbo", "Use English"], doc_only=True, note="Say the language's name. Hausa, Yoruba and Igbo are translated for you.")

# ============================================ hands-free ============================================
cmd("hands.on", "handsfree", "Access granted (hands-free)", "", """access|access granted|grant access|i grant you access|you have access|full access|take control|take over|hands free|hands free on|
go hands free|turn on hands free|take the wheel|you are in control|you are in charge|you drive|run the app for me|operate the app|
you take over|control the app|use the app for me|i give you control|i give you access|i hand over|im handing over|control everything|
you can control everything|go ahead and control|stay on|stay listening|keep listening|keep listening to me|always listen to me""",
    examples=["Access granted", "Hands-free on", "Take control"], doc_only=True, note="After this I keep listening after every answer, so you never need to touch the screen or say my name.")
cmd("hands.off", "handsfree", "That's all (end hands-free)", "", """hands free off|turn off hands free|release access|revoke access|take back control|stand down|thats all|that is all|goodbye|good bye|
im done|i am done|stop listening|thank you that is all|that will be all|we are done|were done|all done|i am finished|im finished|
that is everything|thats everything|nothing else|no more|you can stop|you can rest|go to sleep|rest now|sleep|bye|bye bye|see you|
see you later|good night|goodnight|take a break|go quiet""",
    examples=["That's all", "Goodbye", "Release access"], doc_only=True)

# ============================================ wake and stop (documentation) ============================================
cmd("wake.name", "wake", "Call the assistant", "", """adanna|bola|hey adanna|hey bola|hi adanna|hi bola|ok adanna|ok bola|okay adanna|okay bola|special request|hello adanna|hello bola|
excuse me adanna|excuse me bola|adanna please|bola please|adanna are you there|bola are you there|adanna help|bola help|
adanna listen|bola listen|wake up adanna|wake up bola|adanna wake up|bola wake up""",
    examples=["Adanna", "Hey Bola", "Special request"], doc_only=True, note="Say the name, wait for “Listening”, then say your request. Or say it all at once: “Bola, read this”. The phone must be unlocked with the app open. Teach me your accent in Settings, Teach me your voice.")
cmd("wake.stop", "wake", "Stop talking", "", """stop|quiet|enough|silence|hush|cancel|pause|wait|hold on|hang on|shut up|be quiet|stop talking|stop speaking|that is enough|
thats enough|okay stop|ok stop|stop it|stop now|please stop|enough now|no more|stop reading|stop describing|cut it|zip it|
silence please|quiet please|hush please|stop right there|that will do|thats fine|thats okay|thats ok|alright stop|all right stop|thanks stop""",
    examples=["Stop", "Quiet", "Hold on"], doc_only=True, note="Works while I am talking. On the camera screens, stopping also pauses and listens for your next request.")


# ---- safety: what the emergency parser really understands. Phrases here are all live, none only for show. ----
def _set(id, **kw):
    for c in C:
        if c["id"] == id:
            c.update(kw)
            return
    raise KeyError(id)


for _id in ("safety.where", "safety.sos", "safety.list", "safety.clear", "safety.call"):
    _set(_id, doc_only=False)
_set("safety.sos", phrases=L("""i need help|help me|emergency|sos|send help|call for help|i am in danger|im in danger|i have been attacked|
i am being followed|im being followed|someone is following me|i am scared|im scared|i feel unsafe|i dont feel safe|i am not safe|
im not safe|i have been robbed|someone is attacking me|i was attacked|i need an ambulance|i need the police|i need assistance now|
this is an emergency|it is an emergency|its an emergency|help i need help|please help me|somebody help me|help me please"""))
_set("safety.list", phrases=L("""who is my emergency contact|who are my emergency contacts|read my emergency contacts|list my emergency contacts|
tell me my emergency contact|tell me my emergency contacts|what is my emergency contact|whats my emergency contact|
who is my contact for emergencies|read out my emergency contacts|say my emergency contacts|show my emergency contacts|
check my emergency contact|do i have an emergency contact|who will you text in an emergency|who do you text when i need help"""))
_set("safety.clear", phrases=L("""remove my emergency contact|remove my emergency contacts|delete my emergency contact|delete my emergency contacts|
clear my emergency contacts|clear emergency contacts|remove emergency contacts|clear my emergency contact|forget my emergency contact|
forget my emergency contacts|erase my emergency contact|erase my emergency contacts|get rid of my emergency contact|
get rid of my emergency contacts|delete emergency contacts|delete emergency contact"""))
_set("safety.call", phrases=L("""call 112|dial 112|call 199|call the police|call emergency|call an ambulance|call the emergency number|call the ambulance|
dial the police|dial emergency|dial the ambulance|dial 199|phone the police|phone an ambulance|phone emergency|ring the police|
ring an ambulance|call emergency services|call the emergency services|dial emergency services|call for an ambulance"""))
_set("safety.share", phrases=L("""send my location|share my location|text my location|message my location|send my position|share my position|text my position|
message my position|send location|share location|text location|message location|send my location to|share my location with|
text my location to|message my location to|send my position to|share my position with"""))
_set("safety.setcontact", phrases=L("""set my emergency contact to|set my emergency contact as|add my emergency contact as|make my emergency contact|
change my emergency contact to|save my emergency contact as|set emergency contact to|set my emergency contacts to|my emergency contact is|
add emergency contact as|change emergency contact to|save emergency contact as|make emergency contact|add my emergency contact|
save my emergency contact|set emergency contact as"""))


def _add(id, more):
    for c in C:
        if c["id"] == id:
            c["phrases"] += [p for p in L(more) if p not in c["phrases"]]
            return
    raise KeyError(id)


# "text size" / "font size" are said in many shapes; none of them may ever open the reader.
_add("text.smaller", """reduce the text size|reduce text size|lower the text size|decrease the text size|text size smaller|text size down|text size lower|
reduce the size of the text|decrease the size of the text|lower the size of the text|make the text size smaller|make text size smaller|
smaller text size|reduce the font size|reduce font size|lower the font size|font size smaller|font size down|font size lower|
make the font size smaller|make font size smaller|smaller font size|reduce the size of the font|decrease the size of the font|
lessen the text size|lessen the font size|cut the text size|cut down the text size|shrink the text size|shrink the font size|
text size a bit smaller|text size a little smaller|text a bit smaller|text a little smaller|font a bit smaller|font a little smaller|
less text size|less font size|turn the text size down|turn down the text size|turn the font size down|turn down the font size|
bring the text size down|bring down the text size|take the text size down|text size minus|font size minus|text smaller please""")
_add("text.bigger", """increase the text size|increase text size|raise the text size|text size bigger|text size larger|text size up|text size higher|
increase the size of the text|raise the size of the text|make the text size bigger|make text size bigger|bigger text size|
larger text size|make the font size bigger|make font size bigger|bigger font size|larger font size|font size bigger|font size larger|
font size up|font size higher|raise the font size|raise font size|enlarge the text size|enlarge the font size|boost the text size|
boost the font size|turn the text size up|turn up the text size|turn the font size up|turn up the font size|bring the text size up|
bring up the text size|take the text size up|text size a bit bigger|text size a little bigger|text a bit bigger|text a little bigger|
font a bit bigger|font a little bigger|more text size|more font size|text size plus|font size plus|text bigger please|make the writing bigger|
make the words bigger|make the print bigger|increase the size of the font|increase the print size|bigger print size""")
_add("text.huge", """text size to huge|set the text size to huge|text size to the maximum|set the text size to maximum|maximum text size|
max text size|largest text size|biggest text size|huge text size|font size huge|font size to the maximum|set the font size to maximum|
maximum font size please|largest font size|biggest font size|huge font size|text size as big as it goes|font size as big as it goes""")
_add("text.normal", """set the text size to normal|text size to normal|put the text size back to normal|text size to default|reset text size to normal|
set the font size to normal|font size to normal|put the font size back to normal|font size to default|reset font size to normal|
text size back to default|font size back to default|text size standard|font size standard|normal sized text|default sized text""")

# ---- the emergency contact conversation: "set my emergency contact" with no name starts it ----
cmd("safety.setstart", "safety", "Set my emergency contact (I'll ask you)", "", """set my emergency contact|set emergency contact|add an emergency contact|add emergency contact|new emergency contact|
change my emergency contact|update my emergency contact|edit my emergency contact|i want to set my emergency contact|
i want to add an emergency contact|i want to change my emergency contact|make an emergency contact|create an emergency contact|
save my emergency contact|choose my emergency contact|pick my emergency contact|emergency contact settings|my emergency contact|
emergency contact|set up my emergency contact|set up an emergency contact|register an emergency contact|put in an emergency contact|
enter an emergency contact|help me set my emergency contact|help me add an emergency contact|i need an emergency contact|
who should i set as my emergency contact|let me set my emergency contact|open emergency contact|open the emergency contact""",
    examples=["Set my emergency contact", "Add an emergency contact", "Change my emergency contact"],
    note="I open the Emergency contact page and ask for the name. Say it, or spell it letter by letter. Then say the number digit by digit, and say “save”. You can correct any step by saying “no” or “spell it”.")


# ---- the beep the phone's recogniser makes each time it starts listening ----
cmd("quiet.on", "wake", "Stop the listening beeps", "", """stop beeping|stop the beeping|stop the beep|no beeping|no beep|no beeps|quiet listening|listen quietly|silent listening|
listen without beeping|dont beep|do not beep|stop the sound when listening|turn off the listening sound|no listening sound|
stop the listening beep|stop the listening beeps|mute the beep|mute the beeps|beep off|beeping off|quiet listening on|turn on quiet listening|
enable quiet listening|use quiet listening|listen without sound|listen silently|i hate the beeping|the beeping is annoying|the beep is annoying|
stop that beeping|stop that beep|stop the dinging|no dinging|stop the ding|stop the clicking sound|silence the beep""",
    examples=["Stop the beeping", "Quiet listening", "Listen without beeping"],
    note="I listen for my name with the engine inside the app: silent, but it may mishear my name a little more.")
cmd("quiet.off", "wake", "Normal listening (more exact)", "", """normal listening|listen normally|beep on|beeping on|allow beeping|allow the beep|turn off quiet listening|disable quiet listening|
quiet listening off|more accurate listening|accurate listening|better listening|listen carefully|listen properly|use the phones listening|
use the phone to listen|use your normal listening|go back to normal listening|bring back the beep|bring back the beeping""",
    examples=["Normal listening", "More accurate listening"])

# ---- sleep: stops responding until she is called by name ----
cmd("wake.sleep", "wake", "Sleep (stay quiet until I call you)", "", """sleep|go to sleep|sleep mode|sleep now|adanna sleep|bola sleep|go to sleep adanna|go to sleep bola|goodnight|good night|take a nap|
rest|rest now|have a rest|be quiet until i call you|stay quiet until i call you|quiet until i call you|stop listening until i call you|
stop listening until i call|stop listening until i call your name|dont listen until i call you|do not listen until i call you|
mute yourself|silence until i call you|turn yourself off|switch yourself off|power down|shut down|go quiet|go silent|
go to standby|standby mode|sleep until i call you|sleep until called|take a break|have a break|stand down and sleep|nap time|time to sleep|
go off|log off|go offline|i need quiet|i want quiet|give me quiet|give me some quiet|be silent|stay silent|stay asleep""",
    examples=["Sleep", "Adanna, sleep", "Go to sleep", "Be quiet until I call you"],
    note="I go quiet and ignore everything except my name. Say “Adanna” or “Bola” to wake me.")


def _remove(id, phrases):
    drop = set(L(phrases))
    for c in C:
        if c["id"] == id:
            c["phrases"] = [p for p in c["phrases"] if p not in drop]
            return
    raise KeyError(id)


# "Good night" and friends mean sleep; hands-free ends with "that's all" and "goodbye".
_remove("hands.off", """go to sleep|good night|goodnight|take a break|go quiet|rest now|sleep|you can rest|go to sleep""")
# These were listed for people but only the app's older, narrower wording worked. They are live now, so every one works.
for _id in ("hands.on", "hands.off", "res.retake", "res.repeat", "res.ask"):
    _set(_id, doc_only=False)


# ---- any order, any wording: an object and a direction ----
def _axis(id, objects, directions):
    for c in C:
        if c["id"] == id:
            c["objects"] = L(objects)
            c["directions"] = L(directions)
            return
    raise KeyError(id)


_SCREEN = "brightness|screen brightness|screen|display|backlight|screen light|display brightness"
_SOUND = "volume|sound|audio|speaker|your voice|loudness|the sound|sound level|volume level"
_TEXT = "text|font|letters|writing|print|words|text size|font size|lettering"
_SPEED = "speed|pace|speaking|talking|speech|speaking speed|talking speed|speech speed|speech rate"
_axis("bright.up", _SCREEN, "increase|increased|raise|higher|more|up|boost|brighter|brighten|lift|bump|turn up|put up|increasing|raising")
_axis("bright.down", _SCREEN, "decrease|reduce|lower|less|down|dimmer|dim|lessen|drop|cut|minus|turn down|put down|decreasing|reducing|lowering")
_axis("vol.up", _SOUND, "increase|increased|raise|higher|more|up|louder|boost|bump|lift|turn up|put up|increasing|raising")
_axis("vol.down", _SOUND, "decrease|reduce|lower|less|down|quieter|softer|lessen|drop|cut|minus|turn down|put down|decreasing|reducing|lowering")
_axis("text.bigger", _TEXT, "increase|increased|raise|bigger|larger|enlarge|up|more|higher|boost|magnify|zoom|big|large|increasing|raising|enlarging")
_axis("text.smaller", _TEXT, "decrease|reduce|smaller|lower|shrink|less|down|small|minus|lessen|cut|decreasing|reducing|lowering|shrinking")
_axis("rate.fast", _SPEED, "increase|raise|faster|quicker|up|higher|more|boost|speed up|quick|fast|increasing|raising")
_axis("rate.slow", _SPEED, "decrease|reduce|slower|lower|down|less|slow|lessen|drop|minus|slow down|decreasing|reducing|lowering")


# Phrases that are also ordinary things to say ABOUT a photo ("is it too bright?", "tell me more") or that are too
# easily said by accident. They stay out, so a question stays a question and nothing changes by mistake.
DROP = set(L("""too dark|it is too dark|its too dark|too bright|it is too bright|its too bright|too small|too big|too large|
tell me more about it|say more|talk more|make it longer|more information|more info|more details|tell me|no more|that will do|
thats fine|thats okay|thats ok|a man|the man|a woman|the woman|the male|the female|the lady|the girl|the guy|lady|gentleman|
join|become a member|text|label|scene|environment|one more|update|refresh|stay|play|start|restart|fire|cheese|smile|
darkness|dark side|animate|sleep|rest now|quick|follow up|a question|quick question|main|dismiss|undo|glare|another one"""))


SNAP_NOW = set(L("""take the photo|take the picture|take a photo|take photo|take a picture|take picture|capture|snap|snap it|click|shoot|take it now|press the button|press capture|photo now|picture now|okay take it|shoot it|capture now|take a shot|take shot|take the shot|take it please|please take it|please take the photo|please take the picture|get the shot|get the photo|get the picture|grab the photo|grab the picture|snap the photo|snap a photo|snap a picture|snap the picture|shoot the photo|shoot the picture|go ahead and take it|take the photo now|take the picture now"""))


def normalise(text: str) -> str:
    text = text.lower().replace("'", "").replace("’", "")
    text = re.sub(r"[^a-z0-9 ]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


AXIS_EXCLUDE = L("""read|reads|reading|say|says|said|what|which|who|whom|where|why|how|when|is|are|was|were|does|did|do|would|should|could""")


def match_score(c: dict, text: str) -> tuple[int, int, int] | None:
    """(words, characters) of the longest phrase of [c] found in [text], or None when [c] does not match.

    The longest phrase wins across commands, so "take a picture of me" beats "picture", and "read what is in front of me"
    beats "in front of me". Ties go to the command made for the open screen, then to the one written first.
    """
    padded = f" {text} "
    hits = [normalise(p) for p in c["phrases"] if f" {normalise(p)} " in padded]
    if not hits:
        # "increase the screen brightness a bit", "brightness higher please": an object and a direction, in any order.
        if c["objects"] and 2 <= len(text.split()) <= 9 and not any(f" {w} " in padded for w in AXIS_EXCLUDE)                 and any(f" {normalise(o)} " in padded for o in c["objects"]) and any(f" {normalise(d)} " in padded for d in c["directions"]):
            return (1, 0, 0)
        return None
    if c["verbs"] and len(text.split()) > c["bare"] and not any(f" {normalise(v)} " in padded for v in c["verbs"]):
        return None
    best = max(hits, key=lambda h: (len(h.split()), len(h)))
    # On a tie, the command made for this very screen wins ("photo" on the mirror takes the photo).
    return (len(best.split()), len(best), 1 if c["screens"] else 0)


def first_match(text: str, screen: str | None = None) -> str | None:
    text = normalise(text)
    best, best_score = None, None
    for c in C:
        if c["doc_only"]:
            continue
        if c["screens"] and not (screen and any(screen.startswith(x) for x in c["screens"])):
            continue
        score = match_score(c, text)
        if score is not None and (best_score is None or score > best_score):
            best, best_score = c["id"], score
    return best


def duplicates() -> int:
    """The same phrase in two commands that can both match on the same screen would be a coin toss."""
    seen: dict[str, dict] = {}
    bad = 0
    for c in C:
        if c["doc_only"]:
            continue
        for p in c["phrases"]:
            n = normalise(p)
            other = seen.get(n)
            if other and other["id"] != c["id"] and not (other["screens"] or c["screens"]):
                bad += 1
                print(f"  DUPLICATE  '{n}' is in both {other['id']} and {c['id']}")
            seen.setdefault(n, c)
    return bad


def check() -> int:
    """Every phrase (alone, and after each of the first few verbs) must reach its own command. Prints clashes."""
    bad = 0
    live = [c for c in C if not c["doc_only"]]
    for c in live:
        for p in c["phrases"]:
            sentences = [p] if (not c["verbs"] or len(p.split()) <= c["bare"]) else []
            sentences += [f"{v} {p}" for v in c["verbs"][:4]] if c["verbs"] else []
            for s in sentences:
                got = first_match(s, c["screens"][0] if c["screens"] else None)
                if got != c["id"]:
                    bad += 1
                    if bad <= 60:
                        print(f"  CLASH  '{s}' -> {got} (wanted {c['id']})")
    for c in live:
        for o in c["objects"]:
            for d in c["directions"]:
                for shape in ("{d} the {o}", "{o} {d}", "please {d} the {o} a bit"):
                    sentence = shape.format(d=d, o=o)
                    got = first_match(sentence)
                    if got != c["id"]:
                        bad += 1
                        if bad <= 60:
                            print(f"  CLASH  '{sentence}' -> {got} (wanted {c['id']})")
    return bad


def markdown() -> str:
    q = lambda e: "\u201c" + e + "\u201d"
    lines = [
        "# What you can say to VisionMirror",
        "",
        "Generated from `shared/build_voice_catalog.py` (the same list the app's parser uses). Do not edit by hand.",
        "",
        "Say the assistant's name first (**Adanna** or **Bola**, or **Special request**), then what you want, in the same breath or after",
        q("Listening") + ". You do not need the exact words: each thing below can be said many ways.",
        "",
        "**How matching works.** A sentence matches a command when it contains one of that command's phrases. Where a command has verbs",
        "(" + q("switch to") + ", " + q("change to") + ", " + q("turn on") + "...), any of them works in front of the phrase, and short sentences",
        "need no verb at all (" + q("dark mode") + "). When several commands match, the longest phrase wins.",
        "",
    ]
    for sec in SECTIONS:
        cs = [c for c in C if c["section"] == sec["id"]]
        if not cs:
            continue
        lines += ["## " + sec["title"], "", sec["intro"], ""]
        for c in cs:
            lines.append("### " + c["title"])
            lines.append("")
            lines.append("Say, for example: " + "; ".join(q(e) for e in c["examples"]) + ".")
            if c["note"]:
                lines += ["", c["note"]]
            if c["screens"]:
                lines += ["", "Only on the " + ", ".join(c["screens"]) + " screen."]
            lines += ["", "All " + str(len(c["phrases"])) + " ways: " + ", ".join(c["phrases"]) + ".", ""]
            if c["verbs"]:
                lines += ["Put any of these in front: " + ", ".join(c["verbs"]) + ".", ""]
            if c["objects"]:
                lines += ["Or mix one of these things: " + ", ".join(c["objects"]) + " with one of these words: " + ", ".join(c["directions"]) + ", in any order (for example " + q(c["directions"][0] + " the " + c["objects"][0]) + ").", ""]
    return "\n".join(lines)


def main() -> None:
    for c in C:
        c["phrases"] = [p for p in c["phrases"] if normalise(p) not in DROP]
        if c["id"] == "cam.snap":
            c["phrases"] = [p for p in c["phrases"] if p not in SNAP_NOW]
    ids = [c["id"] for c in C]
    assert len(ids) == len(set(ids)), "duplicate command id"
    for c in C:
        assert c["section"] in {s["id"] for s in SECTIONS}, c["id"]
        assert c["examples"], c["id"]
    bad = check() + duplicates()
    n_phrases = sum(len(c["phrases"]) for c in C)
    print(f"{len(C)} commands, {n_phrases} phrases, {len(CHANGE)} change-verbs, clashes: {bad}")
    if "--check" in sys.argv:
        sys.exit(1 if bad else 0)
    blob = json.dumps({"version": 1, "sections": SECTIONS, "commands": C}, ensure_ascii=False, indent=1) + "\n"
    root = HERE.parent
    for out in (HERE / "voice-commands.json", root / "android" / "app" / "src" / "main" / "assets" / "voice-commands.json"):
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(blob, encoding="utf-8")
        print("wrote", out)
    (root / "docs" / "VOICE_COMMANDS.md").write_text(markdown(), encoding="utf-8")
    print("wrote", root / "docs" / "VOICE_COMMANDS.md")
    sys.exit(1 if bad else 0)


main()
