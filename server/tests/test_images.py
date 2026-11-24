import importlib.util, tempfile, unittest
from pathlib import Path
from PIL import Image

spec = importlib.util.spec_from_file_location('normalize', Path(__file__).parents[1]/'scripts'/'normalize.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class ImageValidation(unittest.TestCase):
    def test_supported_formats_are_decoded_and_normalized(self):
        with tempfile.TemporaryDirectory(prefix='coupon-images-') as folder:
            root=Path(folder)
            for fmt in ['JPEG','PNG','WEBP']:
                source=root/('input.'+fmt.lower()); destination=root/'normalized.png'
                Image.new('RGB',(200,160),'white').save(source,fmt)
                module.normalize(source,destination)
                with Image.open(destination) as result:
                    self.assertEqual(result.format,'PNG');self.assertEqual(result.size,(200,160))

    def test_pixel_bomb_rejected_before_full_decode(self):
        with tempfile.TemporaryDirectory(prefix='coupon-images-') as folder:
            root=Path(folder);source=root/'large.png'
            Image.new('1',(5001,4000)).save(source)
            with self.assertRaises(ValueError): module.normalize(source,root/'output.png')

    def test_unsupported_real_format_and_fake_image_rejected(self):
        with tempfile.TemporaryDirectory(prefix='coupon-images-') as folder:
            root=Path(folder);source=root/'fake.png'
            Image.new('RGB',(10,10)).save(source,'GIF')
            with self.assertRaises(OSError): module.normalize(source,root/'out.png')
            source.write_text('<script>bad</script>')
            with self.assertRaises(OSError): module.normalize(source,root/'out.png')

    def test_transparent_image_is_flattened(self):
        with tempfile.TemporaryDirectory(prefix='coupon-images-') as folder:
            root=Path(folder);source=root/'transparent.png';target=root/'out.png'
            Image.new('RGBA',(20,20),(0,0,0,0)).save(source)
            module.normalize(source,target)
            with Image.open(target) as image:self.assertEqual(image.getpixel((0,0)),(255,255,255))

if __name__=='__main__':unittest.main()
