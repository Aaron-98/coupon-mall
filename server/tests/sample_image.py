
import sys

from PIL import Image, ImageDraw, ImageFont

image = Image.new("RGB", (1150, 620), "#ffffff")
draw = ImageDraw.Draw(image)
font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 65)
for index, line in enumerate(("满100减20元", "有效期：今晚", "适用范围：指定店铺", "商品：声屿耳机")):
    draw.text((50, 32 + index * 138), line, fill="#171717", font=font)
image.save(sys.argv[1])
