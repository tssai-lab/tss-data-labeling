from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).with_name("infer.py")
SPEC = importlib.util.spec_from_file_location("tss_yolov11n_infer", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
infer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(infer)


class InputPreviewTest(unittest.TestCase):

    def test_landscape_and_portrait_are_bounded_and_reencoded_as_rgb_jpeg(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            landscape = root / "landscape.png"
            portrait = root / "portrait.png"
            Image.new("RGBA", (1024, 256), (255, 0, 0, 64)).save(landscape)
            Image.new("RGB", (256, 1024), "blue").save(portrait)

            first = infer.build_input_preview(landscape, root / "out", 0)
            second = infer.build_input_preview(portrait, root / "out", 1)

            self.assertEqual("previews/images/0000.jpg", first["path"])
            self.assertEqual("landscape.png", first["name"])
            with Image.open(root / "out" / first["path"]) as preview:
                self.assertEqual("JPEG", preview.format)
                self.assertEqual("RGB", preview.mode)
                self.assertEqual((512, 128), preview.size)
                self.assertFalse(preview.getexif())
            with Image.open(root / "out" / second["path"]) as preview:
                self.assertEqual((128, 512), preview.size)

    def test_exif_orientation_is_applied_and_metadata_is_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "rotated.jpg"
            image = Image.new("RGB", (80, 40), "green")
            exif = image.getexif()
            exif[274] = 6
            image.save(source, exif=exif)
            image.close()

            result = infer.build_input_preview(source, root / "out", 0)

            with Image.open(root / "out" / result["path"]) as preview:
                self.assertEqual((40, 80), preview.size)
                self.assertFalse(preview.getexif())

    def test_fixed_position_names_avoid_source_name_collisions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_source = root / "one" / "same.png"
            second_source = root / "two" / "same.png"
            first_source.parent.mkdir()
            second_source.parent.mkdir()
            Image.new("RGB", (32, 32), "red").save(first_source)
            Image.new("RGB", (32, 32), "blue").save(second_source)

            first = infer.build_input_preview(first_source, root / "out", 0)
            second = infer.build_input_preview(second_source, root / "out", 1)

            self.assertNotEqual(first["path"], second["path"])
            self.assertTrue((root / "out" / first["path"]).is_file())
            self.assertTrue((root / "out" / second["path"]).is_file())

    def test_corrupt_or_unsupported_input_degrades_without_partial_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            broken = root / "broken.png"
            broken.write_bytes(b"not-an-image")

            result = infer.build_input_preview(broken, root / "out", 0)

            self.assertIsNone(result)
            self.assertFalse((root / "out" / "previews/images/0000.jpg").exists())

    def test_preview_limit_is_enforced_without_creating_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "image.png"
            Image.new("RGB", (16, 16), "black").save(source)

            result = infer.build_input_preview(
                source,
                root / "out",
                infer.INPUT_PREVIEW_LIMIT,
            )

            self.assertIsNone(result)
            self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    unittest.main()
