import os, shutil, subprocess, wave, numpy as np

base='c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'
OUT=os.path.join(base,'real_keyboard')
os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT): os.remove(os.path.join(OUT,f))

srcs=[
    os.path.join(base,'selected_real'),
    os.path.join(base,'selected_mixkit'),
    os.path.join(base,'my','trimmed'),
]
for s in srcs:
    for f in os.listdir(s):
        if f.endswith('.wav') or f.endswith('.ogg'):
            shutil.copy(os.path.join(s,f), os.path.join(OUT,f))

# report durations + onset
def load_wav(path):
    w=wave.open(path,'rb'); n=w.getnframes(); ch=w.getnchannels(); sw=w.getsampwidth()
    raw=w.readframes(n); w.close()
    if sw==2: d=np.frombuffer(raw,dtype=np.int16).astype(float)/32768
    elif sw==3:
        a=np.frombuffer(raw,dtype=np.uint8).reshape(-1,3)
        iii=a[:,0].astype(np.int32)|(a[:,1].astype(np.int32)<<8)|(a[:,2].astype(np.int32)<<16)
        iii=np.where(iii>=2**23,iii-2**24,iii); d=iii.astype(float)/(2**23)
    else: return None
    if ch>1: d=d.reshape(-1,ch).mean(1)
    return d
def onset(d,th=0.20,min_gap=0.05):
    SR=44100; win=int(0.005*SR)
    e=np.array([np.sqrt(np.mean(d[i:i+win]**2)) for i in range(0,len(d)-win,win)]); e=e/np.max(e)
    above=e>th; segs=[]; ins=False
    for i,a in enumerate(above):
        if a and not ins: segs.append([i]); ins=True
        elif not a and ins: segs[-1].append(i); ins=False
    if ins: segs[-1].append(len(above))
    m=[]
    for s in segs:
        if m and (s[0]-m[-1][-1])*0.005<min_gap: m[-1][-1]=s[0]
        else: m.append(list(s))
    return len(m)

print("REAL single-key sounds in real_keyboard/:\n")
for f in sorted(os.listdir(OUT)):
    if not f.endswith('.wav'): continue
    d=load_wav(os.path.join(OUT,f)); dur=len(d)/44100; on=onset(d)
    print(f"  {f:45s} dur={dur*1000:6.0f}ms on={on} peak={np.max(np.abs(d)):.2f}")
print("\nTOTAL wav:", len([x for x in os.listdir(OUT) if x.endswith('.wav')]))
print("ALSO maybe-bucket (short 2-transient):", os.path.join(base,'selected_real_maybe'))
