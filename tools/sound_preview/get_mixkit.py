import urllib.request, re, os, wave, subprocess, time, numpy as np

SR = 44100
DL = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/real_dl_mixkit'
OUT = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/selected_mixkit'
MAYBE = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/selected_mixkit_maybe'
for d in (DL, OUT, MAYBE):
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))

UA = {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

# 1) fetch Mixkit keyboard category, extract preview mp3 urls
req = urllib.request.Request('https://mixkit.co/free-sound-effects/keyboard/', headers=UA)
html = urllib.request.urlopen(req, timeout=30).read().decode('utf-8','ignore')
urls = sorted(set(re.findall(r'https?://assets\.mixkit\.co/active_storage/sfx/\d+/\d+-preview\.mp3', html)))
print(f"Mixkit keyboard preview urls: {len(urls)}", flush=True)

# 2) download
def dl(u, dst):
    try:
        req = urllib.request.Request(u, headers=UA)
        data = urllib.request.urlopen(req, timeout=30).read()
        open(dst,'wb').write(data)
        return len(data)
    except Exception as e:
        return -1

names = []
for i,u in enumerate(urls):
    sid = re.search(r'/sfx/(\d+)/', u).group(1)
    dst = os.path.join(DL, f"mx{sid}.mp3")
    sz = dl(u, dst)
    if sz > 2000:
        names.append(dst); print(f"  dl mx{sid} {sz}B", flush=True)
    else:
        if os.path.exists(dst): os.remove(dst)
        print(f"  dl mx{sid} FAIL", flush=True)
    time.sleep(0.1)
print(f"downloaded {len(names)}", flush=True)

# 3) helpers
def decode_to_wav(src, dstwav):
    return subprocess.run(['ffmpeg','-y','-i',src,'-ar',str(SR),'-ac','1',dstwav], capture_output=True).returncode==0
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
def write_wav(path, sig):
    pcm=(np.clip(sig,-1,1)*32767).astype(np.int16)
    w=wave.open(path,'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm.tobytes()); w.close()
def trim_norm(sig, target=0.85):
    peak=np.max(np.abs(sig))
    if peak<=0: return sig
    thr=max(peak*0.02,1e-4)
    above=np.abs(sig)>thr; idx=np.where(above)[0]
    if len(idx)==0: return sig
    i0=max(0,idx[0]-int(0.002*SR)); i1=min(len(sig)-1,idx[-1]+int(0.002*SR))
    seg=sig[i0:i1+1]
    return seg/np.max(np.abs(seg))*target
def onset_count(d, th=0.20, min_gap=0.05):
    win=int(0.005*SR)
    e=np.array([np.sqrt(np.mean(d[i:i+win]**2)) for i in range(0,len(d)-win,win)])
    e=e/np.max(e); above=e>th; segs=[]; inseg=False
    for i,a in enumerate(above):
        if a and not inseg: segs.append([i]); inseg=True
        elif not a and inseg: segs[-1].append(i); inseg=False
    if inseg: segs[-1].append(len(above))
    m=[]
    for s in segs:
        if m and (s[0]-m[-1][-1])*0.005<min_gap: m[-1][-1]=s[0]
        else: m.append(list(s))
    return len(m)

# 4) process
kept=0; kept_maybe=0
for src in names:
    base=os.path.splitext(os.path.basename(src))[0]
    wavtmp=os.path.join(DL, base+'.wav')
    if not decode_to_wav(src, wavtmp): continue
    d=load_wav(wavtmp)
    if d is None: continue
    dur=len(d)/SR
    on=onset_count(d)
    good=(on==1 and 0.03<=dur<=1.2)
    maybe=(on==2 and dur<=0.5)
    if not (good or maybe):
        print(f"  SKIP {base} on={on} dur={dur*1000:.0f}ms", flush=True)
        os.remove(wavtmp); continue
    seg=trim_norm(d,0.85)
    od=OUT if good else MAYBE
    write_wav(os.path.join(od, base+'.wav'), seg)
    subprocess.run(['ffmpeg','-y','-i',os.path.join(od,base+'.wav'),os.path.join(od,base+'.ogg')], capture_output=True)
    os.remove(wavtmp)
    if good: kept+=1; t="KEEP"
    else: kept_maybe+=1; t="MAYBE"
    print(f"  {t} {base} on={on} dur={len(seg)/SR*1000:.0f}ms", flush=True)

print(f"\nMIXKIT STRICT: {kept} -> {OUT}")
print(f"MIXKIT MAYBE: {kept_maybe} -> {MAYBE}")
