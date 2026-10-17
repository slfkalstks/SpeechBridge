#!/usr/bin/env python3
"""Check documented native palette against runtime values and WCAG text contrast."""
import json, re
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / 'shared/src/commonMain/kotlin/kc/ac/uc/speechbridge/BridgeDesign.kt').read_text()
schemes = {}
colors = {}
for name, prefix in [('BridgePalette', ''), ('BridgeNightPalette', 'dark')]:
    body = source.split('object ' + name + ' {')[1].split('\n}')[0]
    palette = dict(re.findall(r'val (\w+) = Color\(0xFF([0-9A-F]{6})\)', body))
    schemes['dark' if prefix else 'light'] = palette
    colors.update({prefix + key[0].upper() + key[1:] if prefix else key: value for key, value in palette.items()})
documented = dict(re.findall(r'^  (\w+): "#([0-9A-F]{6})"', (root / 'DESIGN.md').read_text(), re.M))
assert colors == documented, 'Runtime/document palette mismatch'
def luminance(hex):
    values = [int(hex[i:i+2],16)/255 for i in (0,2,4)]
    values = [v/12.92 if v <= .04045 else ((v+.055)/1.055)**2.4 for v in values]
    return sum(v*w for v,w in zip(values,(.2126,.7152,.0722)))
pairs = [('onPrimary','primary'),('onPrimaryContainer','primaryContainer'),('ink','surface'),('ink','background'),('ink','translation'),('muted','surface'),('muted','background'),('primary','surface'),('primary','background'),('primary','translation'),('warning','warningContainer'),('warning','surface'),('warning','background'),('danger','surface'),('danger','translation')]
results=[]
for scheme,palette in schemes.items():
    for foreground,background in pairs:
        a,b=sorted([luminance(palette[foreground]),luminance(palette[background])])
        ratio=(b+.05)/(a+.05)
        results.append(dict(scheme=scheme,foreground=foreground,background=background,ratio=round(ratio,2),passes=ratio>=4.5))
print(json.dumps(dict(paletteMatches=True,textContrast=results,scope='Static sRGB normal-text pairs; not screen-reader, focus, touch-target or full WCAG certification'),ensure_ascii=False,indent=2))
assert all(r['passes'] for r in results), 'Normal text contrast below 4.5:1'
