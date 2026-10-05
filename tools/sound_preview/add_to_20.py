import os, shutil, subprocess, wave, numpy as np

base='c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'
RK=os.path.join(base,'real_keyboard')
MAYBE=os.path.join(base,'selected_real_maybe')
SR=44100

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
    seg=sig[i0:i1+1]; return seg/np.max(np.abs(seg))*target

# 1) add the 7 maybe (real short double-transient) files
added=0
for f in sorted(os.listdir(MAYBE)):
    if f.endswith('.wav'):
        dst=os.path.join(RK,'maybe_'+f)
        if not os.path.exists(dst):
            shutil.copy(os.path.join(MAYBE,f), dst)
            # also ogg
            ogg=os.path.join(MAYBE,f[:-4]+'.ogg')
            if os.path.exists(ogg): shutil.copy(ogg, os.path.join(RK,'maybe_'+f[:-4]+'.ogg'))
            added+=1
print(f"added maybe: {added}")

# 2) generate 4 light DSP variants from real bases (keep authentic keyboard timbre)
variants=[
    ("var_a","006.wav","asetrate=44100*1.07,aresample=44100,lowpass=f=3500"),
    ("var_b","015.wav","asetrate=44100*0.93,aresample=44100,lowpass=f=2500"),
    ("var_c","028.wav","asetrate=44100*1.12,aresample=44100,highpass=f=150"),
    ("var_d","mx2541.wav","asetrate=44100*0.90,aresample=44100,lowpass=f=2000"),
]
for name, src, af in variants:
    sin=os.path.join(RK,src); stmp=os.path.join(RK,name+'_tmp.wav'); sout=os.path.join(RK,name+'.wav')
    r=subprocess.run(['ffmpeg','-y','-i',sin,'-af',af,'-ar',str(SR),'-ac','1',stmp],capture_output=True)
    if r.returncode!=0:
        print("FAIL",name,r.stderr[-200:].decode('utf-8','ignore')); continue
    d=load_wav(stmp)
    if d is None: continue
    seg=trim_norm(d,0.85)
    write_wav(sout,seg)
    subprocess.run(['ffmpeg','-y','-i',sout,os.path.join(RK,name+'.ogg')],capture_output=True)
    os.remove(stmp)
    print(f"variant {name} from {src} dur={len(seg)/SR*1000:.0f}ms")

# 3) list all 20
def onset(d,th=0.20,min_gap=0.05):
    win=int(0.005*SR)
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

print("\n=== real_keyboard/ ALL (target 20) ===")
wavs=sorted([f for f in os.listdir(RK) if f.endswith('.wav')])
for f in wavs:
    d=load_wav(os.path.join(RK,f)); dur=len(d)/SR; on=onset(d)
    tag=''
    if f.startswith('maybe'): tag='[maybe:short2-transient]'
    elif f.startswith('var'): tag='[DSP variant of real]'
    print(f"  {f:42s} dur={dur*1000:5.0f}ms on={on} {tag}")
print(f"\nTOTAL wav: {len(wavs)}")
