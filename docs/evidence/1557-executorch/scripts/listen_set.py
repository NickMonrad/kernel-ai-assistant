import subprocess, re, sys
sys.path.insert(0, ".")
import pc_kokoro as pk
OUT = "/home/lokhor/private-acoustic-fixtures/kokoro-executorch-1557"
SENTS = [
 ("en-us", "Hello world."),
 ("en-us", "It happened in 1999."),
 ("en-us", "Pi is roughly 3.14."),
 ("en-us", "We sold 1,000,000 copies."),
 ("en-us", "The 21st century began."),
 ("en-us", "About 50% of people agree."),
 ("en-gb", "It costs \u00a34.50 today."),
 ("en-gb", "The 21st century."),
]
ASSETS = {"en-us": ("hf/phonemizer/en-us/lexicon.json", "hf/phonemizer/en-us/tags.json"),
          "en-gb": ("hf/phonemizer/en-gb/lexicon.json", "hf/phonemizer/en-gb/tags.json")}
def oracle(lang, text):
    lex, tags = ASSETS[lang]
    out = subprocess.run(["phonemis-src/build/phonemis_runner","--lang",lang,"--lexicon",lex,"--tagger",tags,text],capture_output=True,text=True).stdout
    out = re.sub(r"\x1b\[[0-9;]*m", "", out)
    return re.search(r"Output: (.*)", out).group(1).strip()
VOICES = {"en-us": ["af_heart", "am_michael"], "en-gb": ["bf_emma"]}
for i,(lang,text) in enumerate(SENTS):
    ph = oracle(lang, text)
    for v in VOICES[lang]:
        audio, dur_s, _, _ = pk.synth(ph, voice_name=v)
        pk.write_wav(f"{OUT}/pc-{v}-{i:02d}.wav", audio)
        print(v, i, repr(text), ph, round(dur_s, 2), "s")
