
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2] / "assets"


def icon(kind, color):
    image = Image.new("RGBA", (216, 216))
    pen = ImageDraw.Draw(image)
    width = 13
    if kind == "home":
        pen.line([(45, 108), (108, 49), (171, 108)], fill=color, width=width, joint="curve")
        pen.line([(62, 97), (62, 163), (93, 163), (93, 130), (123, 130),
                  (123, 163), (154, 163), (154, 97)], fill=color, width=width, joint="curve")
    elif kind == "radar":
        pen.ellipse((51, 51, 165, 165), outline=color, width=width)
        pen.arc((80, 80, 136, 136), 0, 305, fill=color, width=9)
        pen.line([(108, 108), (158, 58)], fill=color, width=width)
        pen.ellipse((98, 98, 118, 118), fill=color)
    elif kind == "heart":
        pen.line([(108, 163), (57, 116), (50, 94), (59, 74), (77, 65), (96, 69),
                  (108, 82), (120, 69), (139, 65), (157, 74), (166, 94),
                  (159, 116), (108, 163)], fill=color, width=width, joint="curve")
    else:
        pen.ellipse((83, 48, 133, 98), outline=color, width=width)
        pen.arc((53, 111, 163, 194), 192, 348, fill=color, width=width)
    return image.resize((72, 72), Image.Resampling.LANCZOS)


for name in ("home", "radar", "heart", "user"):
    icon(name, "#757283").save(ROOT / (name + ".png"))
    icon(name, "#653dd6").save(ROOT / (name + "-active.png"))
