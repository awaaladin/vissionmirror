"""Downloads the offline speech model (Vosk small English, Indian accent: 36 MB) into the app's assets.

Run once before building on a new machine:  python android/fetch-vosk-model.py
The model is not committed to git because it is large. License: Apache 2.0 (alphacephei.com/vosk/models).
"""
import io, os, sys, urllib.request, zipfile

URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
DEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "assets", "vosk-model")

if os.path.isfile(os.path.join(DEST, "am", "final.mdl")):
    print("model already present:", DEST); sys.exit(0)
print("downloading", URL)
data = urllib.request.urlopen(URL, timeout=300).read()
with zipfile.ZipFile(io.BytesIO(data)) as z:
    top = z.namelist()[0].split("/")[0]
    z.extractall(os.path.dirname(DEST))
os.rename(os.path.join(os.path.dirname(DEST), top), DEST)
print("ready:", DEST)
