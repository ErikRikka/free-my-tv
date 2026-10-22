"""Converts Lucide line icons (24×24 SVG: path, circle, line, polyline, rect) to Android VectorDrawable XML.
Usage: python3 -I svg_to_vector.py IN.svg OUT.xml [STROKE_WIDTH]"""
import re
import sys
import xml.etree.ElementTree as ET


def f(v):
    return float(v or 0)


def to_path(el):
    tag = el.tag.split('}')[-1]
    a = el.attrib
    if tag == 'path':
        return a['d']
    if tag == 'circle':
        cx, cy, r = f(a['cx']), f(a['cy']), f(a['r'])
        return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0"
    if tag == 'line':
        return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if tag in ('polyline', 'polygon'):
        pts = re.findall(r'-?[\d.]+', a['points'])
        pairs = [f"{pts[i]},{pts[i + 1]}" for i in range(0, len(pts), 2)]
        return 'M' + 'L'.join(pairs) + ('Z' if tag == 'polygon' else '')
    if tag == 'rect':
        x, y, w, h = f(a.get('x')), f(a.get('y')), f(a['width']), f(a['height'])
        r = min(f(a.get('rx', a.get('ry', 0))), w / 2, h / 2)
        if r == 0:
            return f"M{x},{y}h{w}v{h}h{-w}Z"
        return (f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r}a{r},{r} 0 0 1 {-r},{r}"
                f"h{-(w - 2 * r)}a{r},{r} 0 0 1 {-r},{-r}v{-(h - 2 * r)}a{r},{r} 0 0 1 {r},{-r}Z")
    return None


def main():
    src, out = sys.argv[1], sys.argv[2]
    width = sys.argv[3] if len(sys.argv) > 3 else '2'
    root = ET.parse(src).getroot()
    paths = [p for p in (to_path(el) for el in root.iter()) if p]
    body = '\n'.join(
        f'    <path android:pathData="{p}" android:strokeColor="#FFFFFFFF" android:strokeWidth="{width}"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />'
        for p in paths)
    open(out, 'w').write(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"\n'
        '    android:viewportWidth="24" android:viewportHeight="24">\n' + body + '\n</vector>\n')


if __name__ == '__main__':
    main()
