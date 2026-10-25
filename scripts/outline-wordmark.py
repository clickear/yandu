"""Optional maintainer helper: outline wordmark with installed licensed system fonts.
Normal brand:generate uses the committed outlined SVG and does not need fonts/Python.
Requires fontTools and Debian/Ubuntu fonts-noto-cjk, fonts-dejavu-core.
"""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

root = Path(__file__).resolve().parents[1]

def outline(font_path, text, size, x, baseline, number=None):
    font = TTFont(font_path, **({'fontNumber': number} if number is not None else {}))
    glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
    scale = size / font['head'].unitsPerEm
    result = []
    for char in text:
        name = cmap[ord(char)]
        pen = SVGPathPen(glyphs)
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        result.append(f'<path d="{pen.getCommands()}"/>')
        x += glyphs[name].width * scale
    return ''.join(result)

paths = outline('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', '言渡', 67, 0, 72, 2)
paths += outline('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 'YANDU', 34, 153, 67)
(root / 'brand/source/wordmark.svg').write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 310 88"><g fill="#24233A">{paths}</g></svg>\n')
