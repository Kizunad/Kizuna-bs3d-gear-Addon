import copy
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("import_bong_wear", Path(__file__).parents[1] / "import_bong_wear.py")
IMPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORTER)


class ImportContractTest(unittest.TestCase):
    def test_mirror_maps_every_face_vertex_and_uv_rotation(self):
        # 将反射前后相同空间顶点的 UV 对拍，覆盖六个面、四种 UV 朝向和非正方贴图。
        vertices = {"west": [0, 4, 6, 2], "east": [5, 1, 3, 7], "north": [1, 0, 2, 3],
                    "south": [4, 5, 7, 6], "up": [2, 6, 7, 3], "down": [1, 5, 4, 0]}
        for rotation in (0, 90, 180, 270):
            cube = {"from": [1, 2, 3], "to": [4, 5, 6], "origin": [7, 8, 9], "rotation": [10, 20, 30],
                    "faces": {name: {"uv": [2, 3, 7, 11], "rotation": rotation} for name in vertices}}
            original = copy.deepcopy(cube)
            IMPORTER.mirror_cube(cube)
            self.assertEqual([-4, 2, 3], cube["from"])
            self.assertEqual([-1, 5, 6], cube["to"])
            self.assertEqual([10, -20, -30], cube["rotation"])
            for face, indices in vertices.items():
                target = {"west": "east", "east": "west"}.get(face, face)
                for index, vertex in enumerate(indices):
                    target_index = vertices[target].index(vertex ^ 1)
                    self.assertEqual(self.uv(original["faces"][face], index),
                                     self.uv(cube["faces"][target], target_index))
            IMPORTER.mirror_cube(cube)
            self.assertEqual(original, cube)

    def test_all_selected_models_preserve_geometry_and_split_bindings(self):
        bong = IMPORTER.ROOT.parent.parent / "Code/Bong"
        for item_id, (name, blob, _) in IMPORTER.SOURCE_BLOBS.items():
            with self.subTest(item=item_id):
                source = IMPORTER.read_source(bong, IMPORTER.source_path(item_id, name), blob)
                original = copy.deepcopy(source)
                model, bindings = IMPORTER.adapt(source, item_id)
                self.assertEqual(original, source, "导入不能改写输入")
                removed = {cid for group in source["outliner"] if group.get("name") == "reference"
                           for cid in IMPORTER.cube_ids(group)}
                expected = {c["uuid"] for c in source["elements"]} - removed
                self.assertEqual(expected, {c["uuid"] for c in model["elements"]})
                self.assertEqual(len(bindings), len(model["outliner"]))
                self.assertEqual(len(bindings), len({b["segment"] for b in bindings}))
                if item_id.startswith("war_"):
                    self.assertNotIn("reference", [g["name"] for g in model["outliner"]])
                for binding in bindings:
                    self.assertIn(binding["bone"], IMPORTER.RIG["bones"])
                self.assertEqual([t["source"] for t in source["textures"]],
                                 [t["source"] for t in model["textures"]])

    def test_paired_parts_keep_anatomical_sides_after_mirroring(self):
        for name, layer, bone in (("arm_r_mount", "right_wrist", "r_forearm"),
                                  ("arm_l_mount", "left_wrist", "l_forearm")):
            self.assertEqual((layer, bone), IMPORTER.binding("pi_sheng_hu_wan", name))
        self.assertEqual(("left_shoulder", "l_upper_arm"),
                         IMPORTER.binding("gu_pian_jian_jia", "plate_top"))

    @staticmethod
    def uv(face, vertex):
        u0, v0, u1, v1 = face["uv"]
        corners = [(u0, v1), (u1, v1), (u1, v0), (u0, v0)]
        return corners[(vertex - face.get("rotation", 0) // 90) % 4]


if __name__ == "__main__":
    unittest.main()
