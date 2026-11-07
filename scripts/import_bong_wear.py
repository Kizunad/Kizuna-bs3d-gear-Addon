#!/usr/bin/env python3
"""从固定 Bong Git 对象导入穿搭；不读取或修改源仓未提交工作区。"""

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE_REF = "c01576513e451490bd73df12513227bb8637222b"
RESOURCE = ROOT / "src/main/resources"
RIG = json.loads((RESOURCE / "assets/bs3d_gear/rig.json").read_text())
SOURCE_BLOBS = {
    "can_bu_tou_jin": ("CanBuTouJin", "5594039d03d519f20959480edcf36bf2b788582b", "残布头巾"),
    "cu_bu_bang_tui": ("CuBuBangTui", "682dcc5b4a2645ef699e64eb0272c3e9f9e4a19d", "粗布绑腿"),
    "cu_bu_mian_jin": ("CuBuMianJin", "e9af01961946c7b831d402b6919bcc3182f2bb37", "粗布面巾"),
    "cu_ma_dou_peng": ("CuMaDouPeng", "dd9b9f6732da9e238cecbe8421fdd9b57aff165a", "粗麻斗篷"),
    "gu_pian_jian_jia": ("GuPianJianJia", "a8b9da7882a67e6ca8dfc8a0b7979be1e30ef611", "骨片肩甲"),
    "ku_cao_guo_jiao": ("KuCaoGuoJiao", "43c092a231605c923059f9e82529535020449808", "枯草裹脚"),
    "pi_sheng_hu_wan": ("PiShengHuWan", "883d7e724beed8a607dab0cb2616dfa77ef342af", "皮绳护腕"),
    "shou_ya_xiang_lian": ("ShouYaXiangLian", "a3637fe605af6eef6350e1fd6c74cdf7ba73b84c", "兽牙项链"),
    "xiu_pian_hu_mu": ("XiuPianHuMu", "35821c574d5430b47804c56ebad3c43d8418eedf", "锈片护目"),
    "war_helm": ("WarHelm", "9387d3ddec4a005f37a3e1975343ce8bda382f93", "战盔"),
    "war_chest": ("WarChest", "c1bbb4591083c11c700c446379a42d411fcc2003", "战甲"),
    "war_legs": ("WarLegs", "a79216855975d4855ddd1edf85a674f3071e2f0d", "战裙与战靴"),
    "war_cloak": ("WarCloak", "e1fff92a6b4e8939b9f47dbfeacd6af7ccf59556", "战披风"),
    "war_suit": ("WarSuit", "6ba71262c855c99cd19d2a035becb434bbb5431e", "战装套装"),
}
ENGLISH = ("Ragged Turban", "Linen Leg Wraps", "Linen Face Wrap", "Coarse Hemp Cloak",
           "Bone Plate Pauldron", "Dry Grass Foot Wraps", "Leather Cord Bracers", "Fang Necklace",
           "Rust Plate Goggles", "War Helm", "War Chest", "War Legs and Boots", "War Cloak", "War Suit")
WAR_BINDINGS = {
    "armorHead": ("armor_head", "head"), "armorBody": ("armor_body", "chest"),
    "armorLeftArm": ("armor_body", "l_upper_arm"), "armorRightArm": ("armor_body", "r_upper_arm"),
    "armorLeftLeg": ("armor_legs", "l_thigh"), "armorRightLeg": ("armor_legs", "r_thigh"),
    "armorLeftBoot": ("armor_feet", "l_foot"), "armorRightBoot": ("armor_feet", "r_foot"),
    "cloak": ("cape", "chest"),
}


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def source_path(item_id, name):
    if item_id.startswith("war_"):
        return f"modelScript/models/armor/warhelm/{name}.bbmodel"
    return f"modelScript/gear/{item_id}/{name}_tex.bbmodel"


def read_source(bong, path, expected):
    data = subprocess.check_output(["git", "-C", str(bong), "show", f"{SOURCE_REF}:{path}"])
    digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if digest != expected:
        raise ValueError(f"源对象不匹配：{path} ({digest})")
    return json.loads(data)


def descendants(group):
    yield group
    for child in group.get("children", []):
        if isinstance(child, dict):
            yield from descendants(child)


def cube_ids(group):
    return [child for node in descendants(group) for child in node.get("children", [])
            if isinstance(child, str)]


def binding(item_id, name):
    if item_id.startswith("war_"):
        return WAR_BINDINGS[name]
    fixed = {"can_bu_tou_jin": ("head", "head"), "xiu_pian_hu_mu": ("eyes", "head"),
             "shou_ya_xiang_lian": ("neck", "chest")}
    if item_id in fixed:
        return fixed[item_id]
    if item_id == "cu_bu_mian_jin":
        return "face", "head" if name == "head_mount" else "chest"
    if item_id == "gu_pian_jian_jia":
        chest = name.startswith("strap_") or name == "chest_anchor"
        return "left_shoulder", "chest" if chest else "l_upper_arm"
    if item_id == "cu_ma_dou_peng":
        if name == "hood":
            return "cape", "head"
        if name in ("mantle_r", "drape_r"):
            return "cape", "r_upper_arm"
        if name in ("mantle_l", "drape_l"):
            return "cape", "l_upper_arm"
        return "cape", "chest"
    paired = {"cu_bu_bang_tui": ("leg", "shin"), "ku_cao_guo_jiao": ("foot", "foot"),
              "pi_sheng_hu_wan": ("wrist", "forearm")}
    layer, bone = paired[item_id]
    side = "r" if "_r_" in name else "l"
    return ("right_" if side == "r" else "left_") + layer, side + "_" + bone


def mirror_cube(cube):
    """反射 x，交换东西面；侧面翻 U、顶底面翻 V，并反转 UV 旋转。"""
    low, high = cube["from"][0], cube["to"][0]
    cube["from"][0], cube["to"][0] = -high, -low
    cube["origin"][0] *= -1
    rotation = cube.setdefault("rotation", [0, 0, 0])
    rotation[1], rotation[2] = -rotation[1], -rotation[2]
    faces = cube["faces"]
    west, east = faces.pop("west", None), faces.pop("east", None)
    if east is not None:
        faces["west"] = east
    if west is not None:
        faces["east"] = west
    for name, face in faces.items():
        if "uv" not in face:
            continue
        a, b = (1, 3) if name in ("up", "down") else (0, 2)
        face["uv"][a], face["uv"][b] = face["uv"][b], face["uv"][a]
        face["rotation"] = (-face.get("rotation", 0)) % 360


def adapt(original, item_id):
    model = copy.deepcopy(original)
    if model.get("animations"):
        raise ValueError("源文件出现动画，必须重新核对转换")
    war = item_id.startswith("war_")
    groups = model["outliner"]
    if not war:
        if len(groups) != 1 or groups[0]["name"] != "root" or any(groups[0].get("rotation", [])):
            raise ValueError("gear 根分组与固定契约不符")
        groups = groups[0]["children"]
    groups = [g for g in groups if g["name"] != "reference"]
    elements = {c["uuid"]: c for c in model["elements"]}
    bindings, selected = [], []
    for group in groups:
        layer, bone = binding(item_id, group["name"])
        pivot = RIG["bones"][bone]["pivot"]
        if group["name"] == "armorHead":
            group["rotation"] = [0, 0, 0]
        for node in descendants(group):
            origin = node.setdefault("origin", [0, 0, 0])
            rotation = node.setdefault("rotation", [0, 0, 0])
            if not war:
                origin[0] *= -1
                rotation[1], rotation[2] = -rotation[1], -rotation[2]
            node["origin"] = [round(v - p, 6) for v, p in zip(origin, pivot)]
        for cube_id in cube_ids(group):
            cube = elements[cube_id]
            if not war:
                mirror_cube(cube)
            for key in ("from", "to", "origin"):
                cube[key] = [round(v - p, 6) for v, p in zip(cube[key], pivot)]
            selected.append(cube)
        bindings.append({"segment": group["name"], "layer": layer, "bone": bone})
    if len({c["uuid"] for c in selected}) != len(selected):
        raise ValueError("分段重复引用立方体")
    model["outliner"], model["elements"] = groups, selected
    for texture in model["textures"]:
        if not texture.get("source", "").startswith("data:image/png;base64,"):
            raise ValueError("缺少内嵌 PNG")
        for key in ("path", "relative_path", "folder"):
            texture.pop(key, None)
    return model, bindings


def build_outputs(bong):
    outputs, sources, languages = {}, [], {"zh_cn": {}, "en_us": {}}
    previews = []
    catalog = ["# 固定版本 Bong 穿搭；几何为骨枢轴局部像素，所有装备当前只提供外观。", "version: 1"]
    for (item_id, (name, blob, title)), english in zip(SOURCE_BLOBS.items(), ENGLISH, strict=True):
        path = source_path(item_id, name)
        model, bindings = adapt(read_source(bong, path, blob), item_id)
        previews.append((item_id, model, bindings))
        outputs[RESOURCE / f"bs3d/gear/models/{name}.bbmodel"] = json_text(model)
        catalog.extend(["items {", f'  id: "bs3d_gear:{item_id}"',
                        '  creative_tab: "bs3d_gear:equipment"', "  wearable {",
                        f'    title: "{title}"', f'    model: "bs3d_gear:{item_id}"'])
        for b in bindings:
            catalog.append('    slots { layer: "%(layer)s" bone: "%(bone)s" segment: "%(segment)s" }' % b)
        catalog.extend([f'    asset {{ file: "models/{name}.bbmodel" mode: MODEL_RENDER_MODE_RAW_SEGMENTS',
                        '      open_animation: "" close_animation: "" }', "  }", "}"])
        outputs[RESOURCE / f"assets/bs3d_gear/items/{item_id}.json"] = json_text({
            "model": {"type": "minecraft:special", "base": f"bs3d_gear:item/{item_id}",
                      "model": {"type": "bs3d_gear:wearable"}}})
        outputs[RESOURCE / f"assets/bs3d_gear/models/item/{item_id}.json"] = json_text({
            "gui_light": "front", "display": {"gui": {"rotation": [10, 35, 0]},
            "firstperson_righthand": {"scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"scale": [0.5, 0.5, 0.5]}}})
        languages["zh_cn"][f"item.bs3d_gear.{item_id}"] = title
        languages["en_us"][f"item.bs3d_gear.{item_id}"] = english
        sources.append({"id": item_id, "path": path, "blob_sha1": blob, "mirror_x": not item_id.startswith("war_"),
                        "cubes": len(model["elements"]), "bindings": bindings})
    outputs[RESOURCE / "bs3d/gear/items.txtpb"] = "\n".join(catalog) + "\n"
    outputs[RESOURCE / "bs3d/gear/sources.json"] = json_text({"repository": "Kizunad/Bong", "commit": SOURCE_REF,
                                                            "models": sources})
    for locale, values in languages.items():
        values["itemGroup.bs3d_gear.equipment"] = "Gear 装备" if locale == "zh_cn" else "Gear Equipment"
        outputs[RESOURCE / f"assets/bs3d_gear/lang/{locale}.json"] = json_text(values)
    outputs[ROOT / "docs/generated/rig-overlays.svg"] = preview_svg(previews)
    return outputs


def rotated(point, node):
    origin = node.get("origin", [0, 0, 0])
    x, y, z = [v - p for v, p in zip(point, origin)]
    rx, ry, rz = map(math.radians, node.get("rotation", [0, 0, 0]))
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return [v + p for v, p in zip((x, y, z), origin)]


def preview_cubes(model, bindings):
    """和 BS3D 一样先应用立方体旋转，再从子到父应用组旋转，最后加回绑定枢轴。"""
    cubes = {cube["uuid"]: cube for cube in model["elements"]}
    result = []

    def visit(group, ancestors, pivot):
        chain = [group] + ancestors
        for child in group["children"]:
            if isinstance(child, dict):
                visit(child, chain, pivot)
                continue
            cube = cubes[child]
            vertices = []
            for corner in range(8):
                point = [cube["to" if corner & (1 << axis) else "from"][axis] for axis in range(3)]
                for node in [cube] + chain:
                    point = rotated(point, node)
                vertices.append([v + p for v, p in zip(point, pivot)])
            result.append(vertices)

    for group, binding in zip(model["outliner"], bindings, strict=True):
        visit(group, [], RIG["bones"][binding["bone"]]["pivot"])
    return result


def preview_svg(previews):
    """静止姿态骨枢轴叠加图；像素坐标不重定比例，可直接检查镜像和段间错位。"""
    row_height, width, scale = 280, 960, 6
    height = 60 + row_height * len(previews)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
           '<rect width="100%" height="100%" fill="#fafafa"/>',
           '<g font-family="sans-serif" fill="#25313d" font-size="14">',
           '<text x="16" y="22">Bong wear / PA bind pivots / front, side, back</text>',
           '<text x="16" y="44">Blue: transformed cube edges. Red: shared PA bone pivots. Units: pixels.</text>']
    edges = [(a, a ^ bit) for a in range(8) for bit in (1, 2, 4) if a < (a ^ bit)]
    chains = [("head", "chest", "waist", "pelvis")]
    for side in ("r", "l"):
        chains.extend([("chest", f"{side}_upper_arm", f"{side}_forearm"),
                       ("pelvis", f"{side}_thigh", f"{side}_shin", f"{side}_foot")])
    for row, (item_id, model, bindings) in enumerate(previews):
        top = 60 + row * row_height
        svg.append(f'<text x="16" y="{top + 16}">{item_id}</text>')
        cubes = preview_cubes(model, bindings)
        for view in range(3):
            def project(point):
                horizontal = (point[0], point[2], -point[0])[view]
                return f'{160 + 320 * view + scale * horizontal:.2f},{top + 236 - scale * point[1]:.2f}'

            paths = []
            for cube in cubes:
                points = [project(point) for point in cube]
                paths.extend(f'M{points[a]}L{points[b]}' for a, b in edges)
            svg.append(f'<path d="{" ".join(paths)}" fill="none" stroke="#447fa4" '
                       'stroke-opacity="0.32" stroke-width="0.55"/>')
            for chain in chains:
                points = " ".join(project(RIG["bones"][name]["pivot"]) for name in chain)
                svg.append(f'<polyline points="{points}" fill="none" stroke="#df9696" stroke-width="1"/>')
            for bone in RIG["bones"].values():
                x, y = project(bone["pivot"]).split(",")
                svg.append(f'<circle cx="{x}" cy="{y}" r="2" fill="#c43e3e"/>')
        svg.append(f'<path d="M16,{top + row_height - 8}H944" stroke="#d9dfe5"/>')
    return "\n".join(svg + ["</g>", "</svg>"]) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bong", type=Path, default=ROOT.parent.parent / "Code/Bong")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = build_outputs(args.bong)
    for path, value in outputs.items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != value:
                raise SystemExit(f"生成资源漂移：{path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value, encoding="utf-8")
    print(f"Bong 穿搭：{len(SOURCE_BLOBS)} 件，{len(outputs)} 个生成资源，固定源校验通过")


if __name__ == "__main__":
    main()
