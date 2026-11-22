"""Renders Glance's icon (brand/glance.svg) as the app's adaptive icon layers.

As the launcher's own icon (vendor/etesian/brand/render.py): the whole squircle icon is the foreground, filling the
72 dp the system shows of the 108 dp layers (a 150-unit window round the 100-unit icon); the background is only the
tile's colours, for any corner a wider mask might show. 432 px: 108 dp at xxxhdpi.

    render.py            (needs cairosvg)
"""
import os
import cairosvg

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'drawable-nodpi')
s = open(os.path.join(HERE, 'glance.svg')).read()
inner = s[s.index('>', s.index('<svg')) + 1:s.rindex('</svg>')]
head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="-25 -25 150 150">'
bg = head + '''<defs><linearGradient id="b" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="35" y2="100">
<stop offset="0" stop-color="#FFB03A"/><stop offset=".48" stop-color="#F2486A"/><stop offset="1" stop-color="#7B2FD0"/>
</linearGradient></defs><rect x="-25" y="-25" width="150" height="150" fill="url(#b)"/></svg>'''
cairosvg.svg2png(bytestring=bg.encode(), write_to=os.path.join(OUT, 'ic_glance_bg.png'), output_width=432, output_height=432)
cairosvg.svg2png(bytestring=(head + inner + '</svg>').encode(), write_to=os.path.join(OUT, 'ic_glance_fg.png'),
                 output_width=432, output_height=432)
