# ASCENT launcher icon: one geometry -> SVG master, Android vector drawables, PNGs.
# Canvas is the adaptive-icon 108x108 grid; keep art inside the 66-unit safe circle.
import os, cairosvg
C = 54.0
HEX = "M54,28 L76.517,41 L76.517,67 L54,80 L31.483,67 L31.483,41 Z"   # pointy-top, R=26
CHEV = "M41.5,69 L54,49 L66.5,69"                                       # the ascent mark
DIAM = "M54,36.3 L57.2,39.5 L54,42.7 L50.8,39.5 Z"                      # the System's ◆
BG0, BG1 = "#123466", "#04060C"
# (path, color, width, alpha, join) — wide translucent strokes fake a glow
STROKES = [
  (HEX, "#2E8BFF", 7.0, 0.10, "round"), (HEX, "#2E8BFF", 4.5, 0.22, "round"),
  (HEX, "#2E8BFF", 2.6, 1.00, "miter"), (HEX, "#A9D4FF", 0.9, 0.85, "miter"),
  (CHEV, "#00D4FF", 8.0, 0.12, "round"), (CHEV, "#00D4FF", 5.0, 0.30, "round"),
  (CHEV, "#00D4FF", 3.2, 1.00, "miter"), (CHEV, "#E8FBFF", 1.2, 1.00, "miter"),
  (DIAM, "#00D4FF", 2.6, 0.35, "round"),
]
FILLS = [(DIAM, "#E8FBFF", 1.0)]

def svg(full=True):
    s = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">']
    if full:
        s.append(f'<defs><radialGradient id="bg" cx="54" cy="46" r="64" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{BG0}"/><stop offset="1" stop-color="{BG1}"/></radialGradient></defs>')
        s.append('<rect width="108" height="108" fill="url(#bg)"/>')
    for d,c,w,a,j in STROKES:
        s.append(f'<path d="{d}" fill="none" stroke="{c}" stroke-opacity="{a}" stroke-width="{w}" '
                 f'stroke-linejoin="{j}" stroke-linecap="butt" stroke-miterlimit="10"/>')
    for d,c,a in FILLS:
        s.append(f'<path d="{d}" fill="{c}" fill-opacity="{a}"/>')
    s.append('</svg>')
    return "\n".join(s)

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
HEAD = lambda extra="": (f'<vector {NS}{extra}\n    android:width="108dp" android:height="108dp"\n'
                         f'    android:viewportWidth="108" android:viewportHeight="108">\n')
def argb(c, a=1.0): return "#%02X%s" % (round(a*255), c[1:].upper())

bg = (HEAD(' xmlns:aapt="http://schemas.android.com/aapt"') +
      '    <path android:pathData="M0,0h108v108h-108z">\n'
      '        <aapt:attr name="android:fillColor">\n'
      f'            <gradient android:type="radial" android:centerX="54" android:centerY="46"\n'
      f'                android:gradientRadius="64" android:startColor="{argb(BG0)}" android:endColor="{argb(BG1)}"/>\n'
      '        </aapt:attr>\n    </path>\n</vector>\n')
fg = HEAD()
for d,c,w,a,j in STROKES:
    fg += (f'    <path android:pathData="{d}" android:fillColor="#00000000"\n'
           f'        android:strokeColor="{argb(c)}" android:strokeAlpha="{a}" android:strokeWidth="{w}"\n'
           f'        android:strokeLineJoin="{j}" android:strokeLineCap="butt" android:strokeMiterLimit="10"/>\n')
for d,c,a in FILLS:
    fg += f'    <path android:pathData="{d}" android:fillColor="{argb(c)}" android:fillAlpha="{a}"/>\n'
fg += '</vector>\n'
mono = HEAD()
for d,w in ((HEX,3.0),(CHEV,4.0)):
    mono += (f'    <path android:pathData="{d}" android:fillColor="#00000000" android:strokeColor="#FF000000"\n'
             f'        android:strokeWidth="{w}" android:strokeLineJoin="miter" android:strokeLineCap="butt"/>\n')
mono += f'    <path android:pathData="{DIAM}" android:fillColor="#FF000000"/>\n</vector>\n'
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon ' + NS + '>\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
            '</adaptive-icon>\n')

out = "/home/claude/icon/res"
for sub in ("drawable","mipmap-anydpi-v26"): os.makedirs(f"{out}/{sub}", exist_ok=True)
open(f"{out}/drawable/ic_launcher_background.xml","w").write('<?xml version="1.0" encoding="utf-8"?>\n'+bg)
open(f"{out}/drawable/ic_launcher_foreground.xml","w").write('<?xml version="1.0" encoding="utf-8"?>\n'+fg)
open(f"{out}/drawable/ic_launcher_monochrome.xml","w").write('<?xml version="1.0" encoding="utf-8"?>\n'+mono)
open(f"{out}/mipmap-anydpi-v26/ic_launcher.xml","w").write(adaptive)
open(f"{out}/mipmap-anydpi-v26/ic_launcher_round.xml","w").write(adaptive)
open("/home/claude/icon/ascent-icon-master.svg","w").write(svg(True))

# Full-bleed 512 (store/listing art) and legacy mipmaps (visible 72/108 region, rounded square)
cairosvg.svg2png(bytestring=svg(True).encode(), write_to="/home/claude/icon/ascent-icon-512.png",
                 output_width=512, output_height=512)
from PIL import Image, ImageDraw
full = Image.open("/home/claude/icon/ascent-icon-512.png").convert("RGBA")
m = 512*18/108                          # crop the 18-unit border outside the visible 72-unit region
crop = full.crop((round(m),round(m),round(512-m),round(512-m)))
for name,px in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    os.makedirs(f"{out}/mipmap-{name}", exist_ok=True)
    img = crop.resize((px,px), Image.LANCZOS)
    for fn,radius in (("ic_launcher.png",px*0.22),("ic_launcher_round.png",px/2)):
        mask = Image.new("L",(px,px),0); ImageDraw.Draw(mask).rounded_rectangle((0,0,px-1,px-1),radius=radius,fill=255)
        o = Image.new("RGBA",(px,px),(0,0,0,0)); o.paste(img,(0,0),mask); o.save(f"{out}/mipmap-{name}/{fn}")
print("ok")
