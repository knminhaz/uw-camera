"""Reproducible README figures from archived pixels. No generative editing or extra enhancement."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse, hashlib, json, textwrap
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
CASES=[
 ('1792920192129-c94fec-3fa7a525','fabric-detail','Indoor scene','Indoor scene; leaf-pattern fabric and shelving.',(300,828,428,956)),
 ('1792952814379-196fe7-64446bf1','packaging-detail','Printed packaging','Indoor scene; small printed detail on packaging.',(408,636,536,764)),
 ('1792953137936-636bb6-6683b6ea','screen-detail','Screen detail','Photographed display; screen moire and resampling can affect appearance.',(624,396,752,524)),
 ('1792953727412-5706f0-6912899c','low-light-dog','Dog figure / very low light','Very low light, as reported during capture; no lux measurement.',(336,660,464,788)),
]
BG='#f4f6fa';INK='#15253a';MUTED='#536478';BLUE='#2058ac';GREEN='#0c885c';WHITE='#ffffff'
def font(size,bold=False):
    win=Path('C:/Windows/Fonts')/('segoeuib.ttf' if bold else 'segoeui.ttf')
    if win.exists():return ImageFont.truetype(str(win),size)
    return ImageFont.truetype('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf',size)
def text(draw,xy,value,size=20,color=INK,bold=False):draw.text(xy,str(value),font=font(size,bold),fill=color)
def wrapped(draw,xy,value,width=43,size=21,color=MUTED):
    y=xy[1]
    for line in textwrap.wrap(value,width=width):text(draw,(xy[0],y),line,size,color);y+=size+9
    return y

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def changed(a,b):return float(np.any(np.abs(a.astype(np.int16)-b.astype(np.int16))>=2,axis=2).mean()*100)
def overlay(image,mask):
    base=np.asarray(image,dtype=float);alpha=np.asarray(mask,dtype=float)[...,None]/255*.45
    color=np.array([0,235,125],float)
    return Image.fromarray(np.round(base*(1-alpha)+color*alpha).astype('uint8'))
def roi_box(draw,roi,xy,scale):
    x0,y0,x1,y1=roi;ox,oy=xy
    box=(round(ox+x0*scale),round(oy+y0*scale),round(ox+x1*scale),round(oy+y1*scale))
    draw.rectangle(box,outline='white',width=6);draw.rectangle(box,outline='#00c480',width=3)
    return box

def build(folder,slug,title,note,roi,destination):
    files={n:folder/n for n in ['comparison-original.png','prepared-ultrawide.png','baseline.png','result.png','confidence.png','processing.json','capture.json']}
    images={n:Image.open(p).convert('RGB') for n,p in files.items() if p.suffix=='.png'}
    before=images['comparison-original.png'];final=images['result.png'];baseline=images['baseline.png']
    assert before.size==final.size==baseline.size==images['confidence.png'].size
    mask=images['confidence.png'].convert('L');x0,y0,x1,y1=roi
    assert (x1-x0,y1-y0)==(128,128)
    assert 0<=x0<x1<=before.width and 0<=y0<y1<=before.height
    proc=json.loads(files['processing.json'].read_text(encoding='utf-8'))
    final_change=changed(np.asarray(images['prepared-ultrawide.png']),np.asarray(final))
    baseline_change=proc['fusionMetrics']['changedPixelPercent'];coverage=proc['fusionMetrics']['referenceAreaPercent']
    stamp=datetime.fromtimestamp(int(folder.name.split('-')[0])/1000,timezone(timedelta(hours=5,minutes=30))).strftime('%d %b %Y, %I:%M:%S %p IST')
    canvas=Image.new('RGB',(1536,1580),BG);d=ImageDraw.Draw(canvas)
    text(d,(32,18),'UW camera / '+title,38,bold=True)
    text(d,(32,74),f'Captured {stamp}  |  {before.width} x {before.height} processing pixels',21)
    stats=[('Trusted reference area',f'{coverage:.2f}%'),('Pixels changed / final',f'{final_change:.2f}%'),('Pixels changed / fusion',f'{baseline_change:.2f}%'),('Processing time',f'{proc["processingDurationMs"]/1000:.1f} s')]
    for i,(label,value) in enumerate(stats):
        x=32+i*376;d.rounded_rectangle((x,118,x+344,218),radius=12,fill=WHITE)
        text(d,(x+18,130),label,19,MUTED);text(d,(x+18,160),value,31,BLUE,True)
    text(d,(32,245),'Full view / original | final',23,bold=True)
    text(d,(448,245),'Original ultrawide / 4x detail',23,bold=True)
    text(d,(992,245),'Final: fusion + AI / 4x detail',23,bold=True)
    split=before.copy();split.paste(final.crop((final.width//2,0,final.width,final.height)),(final.width//2,0))
    canvas.paste(split.resize((384,512),Image.Resampling.LANCZOS),(32,284));d.line((224,284,224,796),fill='white',width=3)
    bounds=roi_box(d,roi,(32,284),384/before.width)
    for x,source in [(448,before),(992,final)]:
        canvas.paste(source.crop(roi).resize((512,512),Image.Resampling.NEAREST),(x,284))
        d.rectangle((x,284,x+511,795),outline='#c7d1df',width=1)
    # Explicitly connect the marked full-frame region to the enlarged comparison.
    d.line((bounds[2],(bounds[1]+bounds[3])//2,432,(bounds[1]+bounds[3])//2,432,540,448,540),fill=GREEN,width=2)
    text(d,(32,813),'Green box = detail region',20,GREEN,True)
    text(d,(448,813),f'Identical 128 x 128 crop at x={x0}, y={y0}; both enlarged exactly 4x.',21)
    text(d,(32,855),note,22,BLUE,True)
    text(d,(32,910),'Confidence / full view',25,bold=True)
    text(d,(448,910),'Same region / green overlay / 4x',25,bold=True)
    text(d,(992,910),'How to read these figures',25,bold=True)
    tinted=overlay(final,mask)
    canvas.paste(tinted.resize((384,512),Image.Resampling.LANCZOS),(32,954));roi_box(d,roi,(32,954),384/before.width)
    canvas.paste(tinted.crop(roi).resize((512,512),Image.Resampling.NEAREST),(448,954))
    d.rounded_rectangle((992,954,1504,1466),radius=12,fill=WHITE)
    y=974
    items=[('Green = confidence','Overlay opacity follows saved reference confidence. Maximum green opacity is 45%. It is not an improvement heatmap.'),('Percentages = activity','Changed pixels differ from prepared ultrawide by at least 2/255 in any RGB channel. Percentages cover the full frame.'),('No extra enhancement','Both crops use the same coordinates and nearest-neighbor 4x enlargement. No exposure, color, contrast or sharpening edits were added.'),('Visual example only','Sharper-looking edges do not prove recovered detail is correct. No aligned ground truth, PSNR or SSIM is available for this capture.')]
    for heading,body in items:
        text(d,(1012,y),heading,22,INK,True);y+=32
        y=wrapped(d,(1012,y),body,width=48,size=18)+14
    text(d,(32,1493),'Figure editing: crop, equal resizing, compositing and annotation only. Processing output includes labeled AI restoration.',20)
    text(d,(32,1527),'Archive: '+folder.name,18,MUTED)
    target=destination/(slug+'.png');canvas.save(target,optimize=True)
    detail=Image.new('RGB',(1056,590),BG);dd=ImageDraw.Draw(detail)
    text(dd,(8,5),title+' / identical 4x detail',25,bold=True)
    for x,source,label in [(8,before,'Original ultrawide'),(536,final,'Fusion + AI output')]:
        text(dd,(x,44),label,20);detail.paste(source.crop(roi).resize((512,512),Image.Resampling.NEAREST),(x,78))
    detail.save(destination/(slug+'-preview.png'))
    manifest={'sessionId':folder.name,'scene':title,'captureDateIST':stamp,'note':note,'cropXYXY':roi,'cropScale':4,'interpolation':'nearest','cropSelection':'Selected for visible differences inside a trusted region; not a random benchmark sample','canvasSize':before.size,'referenceCoveragePercent':coverage,'baselineChangedPixelsPercent':baseline_change,'finalChangedPixelsPercent':final_change,'changeThreshold':'at least 2/255 in any RGB channel vs prepared-ultrawide.png, full canvas','processingMs':proc['processingDurationMs'],'meanCropConfidence':float(np.asarray(mask.crop(roi)).mean()/255),'qualityImprovementPercent':None,'groundTruthAvailable':False,'sourceSha256':{name:sha(path) for name,path in files.items()},'figure':target.name,'figureSha256':sha(target)}
    return manifest

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--archive',type=Path,default=ROOT/'desktop-data/sessions');parser.add_argument('--output',type=Path,default=ROOT/'docs/images');args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    figures=[]
    for session,slug,title,note,roi in CASES:
        figures.append(build(args.archive/session,slug,title,note,roi,args.output))
    (args.output/'comparison-manifest.json').write_text(json.dumps({'method':'Pixel compositing only; no generative image editing. Original capture pipeline includes the labeled Real-ESRGAN stage.','figures':figures},indent=2),encoding='utf-8')
    print(json.dumps([{k:f[k] for k in ['scene','captureDateIST','referenceCoveragePercent','finalChangedPixelsPercent','meanCropConfidence']} for f in figures],indent=2))
if __name__=='__main__':main()
