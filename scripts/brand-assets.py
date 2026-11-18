"""Package the generated mark for Android, with a 48 dp mark on a 108 dp adaptive layer."""
from pathlib import Path
from PIL import Image
import math
root=Path(__file__).resolve().parent.parent
source=Image.open(root/'artifacts/branding-2.0.9/logo-source.png').convert('RGBA')
r,g,b,a=source.split()
mask=r.point(lambda value:255 if value>=100 else 0)
box=mask.getbbox()
assert box
mark=source.copy();mark.putalpha(mask);mark=mark.crop(box)
# Android adaptive layers are 108 dp; the safe mark is 48 dp, leaving room even in circle masks.
canvas=512
edge=round(canvas*48/108)
mark.thumbnail((edge,edge),Image.Resampling.LANCZOS)
position=((canvas-mark.width)//2,(canvas-mark.height)//2)
foreground=Image.new('RGBA',(canvas,canvas),(0,0,0,0));foreground.alpha_composite(mark,position)
res=root/'android/app/src/main/res/drawable-nodpi'
foreground.save(res/'brand_mark.png',optimize=True)
mono=Image.new('RGBA',(canvas,canvas),(255,255,255,0));mono.putalpha(foreground.getchannel('A'))
mono.save(res/'brand_monochrome.png',optimize=True)
# Notification assets have a separate 24 dp usage and retain a 2 dp inner margin.
glyph=Image.new('RGBA',mask.size,(255,255,255,0));glyph.putalpha(mask);glyph=glyph.crop(box);glyph.thumbnail((80,80),Image.Resampling.LANCZOS)
notification=Image.new('RGBA',(96,96),(255,255,255,0));notification.alpha_composite(glyph,((96-glyph.width)//2,(96-glyph.height)//2));notification.save(res/'ic_notification_frame.png',optimize=True)
# Store artwork shows the same apparent scale as the launcher, without an embedded rounded mask.
store=Image.new('RGBA',(512,512),(25,134,81,255));store.alpha_composite(foreground.resize((768,768),Image.Resampling.LANCZOS),(-128,-128));store.convert('RGB').save(root/'artifacts/branding-2.0.9/play-icon-512.png',optimize=True)
# Verify the adaptive layer after Android's 25% overscan on each side remains inside a circular mask.
alpha=foreground.getchannel('A');radius=0
for y in range(canvas):
 for x in range(canvas):
  if alpha.getpixel((x,y))>=128:
   u=(x+.5)/canvas-.5;v=(y+.5)/canvas-.5
   radius=max(radius,math.hypot(u*1.5,v*1.5))
assert radius<.47,(radius,'circular mask safety failed')
print(f'Mark: {mark.width/canvas*108:.2f} × {mark.height/canvas*108:.2f} dp on 108 dp; circular radial clearance: {(0.5-radius)*100:.2f}% of icon side')
(root/'artifacts/branding-2.0.9/safety.txt').write_text(f'Layer: 108 × 108 dp\nMark: {mark.width/canvas*108:.2f} × {mark.height/canvas*108:.2f} dp\nCentered bounding box: {alpha.getbbox()} / 512\nAndroid adaptive overscan: 25% per side\nCircular radial clearance: {(0.5-radius)*100:.2f}% of icon side\nCircle clipping: none at alpha >= 128\n')
