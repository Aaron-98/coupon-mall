"""Deterministic, synthetic labelled set. No real customer data."""
import json, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

folder = Path(sys.argv[1]); folder.mkdir(parents=True, exist_ok=True)
rows = []
categories = ['clear']*50 + ['blurred']*30 + ['long']*30 + ['relative']*30 + ['complex']*30 + ['no_coupon']*30
for i, category in enumerate(categories):
    threshold, discount = str(100+i), str(10+i%25)
    period = '今晚' if category == 'relative' else '2026年12月31日'
    scope = '指定店铺'
    lines = [f'满{threshold}减{discount}元', f'有效期：{period}', f'适用范围：{scope}', '商品：声屿耳机']
    truth = dict(discount=discount, threshold=threshold, period=period, scope=scope)
    if category == 'complex':
        lines.insert(1, f'满{int(threshold)+100}减{int(discount)+10}元')
        truth.update(discount=None, threshold=None)
    if category == 'no_coupon':
        lines = ['新品展示', '请理性购物', '商品：声屿耳机']
        truth = dict.fromkeys(truth)
    height = 2800 if category == 'long' else 780
    image = Image.new('RGB', (1200, height), 'white')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 52+i%3*4)
    gap = 520 if category == 'long' else 135
    for index, line in enumerate(lines):
        draw.text((45+i%5*4, 35+index*gap), line, fill='#171717', font=font)
    if category == 'blurred': image = image.filter(ImageFilter.GaussianBlur(1.4))
    filename = f'{i:03d}-{category}.png'; image.save(folder/filename)
    rows.append(dict(id=str(i), category=category, image=filename, truth=truth))
(folder/'manifest.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Generated {len(rows)} labelled synthetic screenshots')
