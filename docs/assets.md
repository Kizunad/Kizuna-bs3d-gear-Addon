# 穿搭资产来源

导入源仓为 `Kizunad/Bong`，固定提交 `c01576513e451490bd73df12513227bb8637222b`。每个文件的路径、Git blob SHA-1、立方体数和逐段绑定记录在 `src/main/resources/bs3d/gear/sources.json`。本仓代码使用根目录 MIT License；导入模型与纹理保留 Bong 原作者来源，不因代码许可证而声明源资产取得了新的独立授权。固定提交根目录没有单独 LICENSE 文件。

## 确定性转换

1. `git show <commit>:<path>` 获取源 JSON，重算 Git blob 摘要并与固定表对拍。源仓脏工作区不参与导入。
2. 普通 gear 提升未旋转 root 的直属子组，沿 X 反射立方体、组枢轴和旋转 Y/Z。交换东西面；侧面翻 U、顶底面翻 V；反转面 UV 旋转。六面、四个 UV 朝向均有逐顶点对拍测试。
3. War 坐标不镜像，清除 WarHelm/WarSuit 的 armorHead 编辑残余旋转，移除 WarSuit 的 reference 组及其五个参考方块。可见模型与内嵌 PNG 保留；只清除机器相关外部纹理路径。
4. 每个顶层分组减去绑定骨枢轴，内部组链和 UUID 保留。`raw_segments` 阻止 BS3D 自动居中或缩放。跨关节形状目前只做刚性跟骨。
5. `assets/bs3d_gear/rig.json` 同时供导入脚本和 Java 使用。clientTest 对拍实际 PA 骨架的枢轴，并以真实骨矩阵变换采样点校验转换。
6. 导入脚本输出 `docs/generated/rig-overlays.svg`，叠加变换后全部立方体边线和 PA 绑定骨枢轴。此图用于静态坐标检查，不能代替运行中的贴图和动画截图。

## 模型边界

- 枯藤腰带属于 BS3D 容器，未复制成本仓穿搭。骨灯挂杆未选，不导入。
- WarSuit 是所选组合模型，同时占四甲层与 cape；独立 War 单件保留自己的槽层。所有层冲突与库存原子性由 BS3D 统一负责。
- WarCloak 风/走/跑/骑变体是 G3 姿态库输入；本轮只导入静止 WarCloak，没有提取或重建动态骨链。
- 披风保持导入的骨局部位置；背负容器不会使披风后片向后平移。
- 头巾尾、项链、粗麻斗篷内部层级完整保留，但无运行时摆动。护腕跨腕、War 腿甲跨膝的形状在大角度动作下仍可能穿模，跨关节蒙皮属于 G4。
