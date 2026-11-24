import sys
from PIL import Image, ImageOps


def normalize(source, destination):
    with Image.open(source, formats=["JPEG", "PNG", "WEBP"]) as image:
        if image.width * image.height > 20_000_000 or getattr(image, "n_frames", 1) != 1:
            raise ValueError("Image limits exceeded")
        image = ImageOps.exif_transpose(image).convert("RGBA")
        image.thumbnail((3600, 3600), Image.Resampling.LANCZOS)
        background = Image.new("RGB", image.size, "white")
        background.paste(image, mask=image.getchannel("A"))
        background.save(destination, "PNG")


if __name__ == "__main__":
    try:
        normalize(*sys.argv[1:])
    except (OSError, ValueError, Image.DecompressionBombError):
        sys.exit(2)
