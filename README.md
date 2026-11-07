# Kizuna BS3D Gear Addon

Minecraft 26.2 / Fabric / Java 25 的穿搭附属。物品、穿脱、层冲突、保存和公开外观同步复用 Backpack and Storage 3D；本仓负责 Bong 穿搭资产、物品模型、PA 刚性绑定和原版模型回退。

当前包含 G2 服务端接线：War 按实际 BS3D 甲层覆盖应用铁甲防御，与原版同区装备双向互斥；Weights 统计每个实际穿搭栈一次并提供有效负重。披风、头巾尾、项链和腰带挂件均保持静态。

## 安装与物品

- 客户端与服务端安装同版 `kizuna-bs3d-gear-addon-0.1.0-SNAPSHOT.jar`，并同步安装含 G2 互斥与装备通知的 BS3D（业务协议 v8 / 玩家保存格式 4）。
- 客户端还需 BS3D client、Inventory UI client/server、owo 及各自依赖；专用服务端需 BS3D server 和 Inventory UI server。Fabric API 两端必需。
- Players Animation 可选。安装时按当前实体 `EntityFrame` 跟随骨骼；物理缺包或该实体没有帧时使用原版 `ModelPart`。生产 JAR 不内嵌 PA。
- 不与旧 `kpa_bs3d_swing` 同装，清单明确拒绝重复挂接。合并边界见 [迁移说明](docs/migration.md)。
- 在独立的 **Gear 装备** 创造栏取得 14 件正式装备。将手中或容器内装备拖到 BS3D 人物模型即可穿戴；右键模型打开“脱掉 XXX”列表，选择后取回合法原版主背包槽位。独立背包页、工作台和安装新版 Vanilla Addon 后的原版菜单共用交互，满背包等拒绝保留原物品。

| 物品 ID（`bs3d_gear:`） | 外观 / 层 |
|---|---|
| `can_bu_tou_jin` | 残布头巾 / head |
| `xiu_pian_hu_mu` | 锈片护目 / eyes |
| `cu_bu_mian_jin` | 粗布面巾 / face，头部和胸部拆段 |
| `shou_ya_xiang_lian` | 兽牙项链 / neck |
| `gu_pian_jian_jia` | 骨片肩甲 / left_shoulder，绑带跟胸、甲片跟左上臂 |
| `pi_sheng_hu_wan` | 皮绳护腕 / 左右腕，跟左右前臂 |
| `cu_bu_bang_tui` | 粗布绑腿 / 左右腿，跟左右小腿 |
| `ku_cao_guo_jiao` | 枯草裹脚 / 左右脚 |
| `cu_ma_dou_peng` | 粗麻斗篷 / cape，头罩、左右肩、胸背拆段 |
| `war_helm` | 战盔 / armor_head |
| `war_chest` | 战甲 / armor_body，胸与双上臂拆段 |
| `war_legs` | 战裙与战靴 / armor_legs + armor_feet |
| `war_cloak` | 战披风 / cape |
| `war_suit` | 战装组合模型 / 四个甲层 + cape，原子占用 |

普通外观实际为 9 件。原挑选文档的“gear 11 件”包含已移交 BS3D 的枯藤腰带和未选骨灯挂杆；加 War 五个选定源模型后，本仓共 14 件。WarChest 属于 War 套；WarSuit 保留所选组合模型，与其单件因相同占层互斥。

## 资产和坐标

唯一导入入口为 `scripts/import_bong_wear.py`，从固定 Bong Git 提交和 blob 读取，不访问源仓未提交模型。来源、清理和署名见 [资产说明](docs/assets.md)。

```bash
python3 "scripts/import_bong_wear.py" --bong "/home/kiz/Code/Bong"
python3 "scripts/import_bong_wear.py" --bong "/home/kiz/Code/Bong" --check
python3 -m unittest discover -s "scripts/tests"
```

脚本生成 14 个 `.bbmodel`、穿戴清单、物品入口/展示模型、中英翻译、来源记录及 [骨枢轴叠加三视图](docs/generated/rig-overlays.svg)。不要手改生成文件。

gear 沿 X 镜像，包含旋转和逐面 UV；War 不镜像。每个顶层段减去 `rig.json` 中的骨枢轴。渲染使用 PA 全局姿态增量 × 骨枢轴 × 像素转换；PA 帧已含原版姿态，不再重复乘原版部件。回退只用对应部件矩阵加枢轴差值。世界渲染和物品预览共用分段校验；错误覆盖只停用该件，重载后按新资源实例恢复。

## 构建与验证

工作区依赖路径由 Meridian Framework 的 `scripts/workspace.gradle` 解析。框架目录位于同级；本工作区的其他依赖位于 `/home/kiz/Code`。组合构建必须在命令行显式传 `kizuna.dir.bs3d`，确保 PA 的间接依赖也使用 G0 worktree。

每次 Gradle 前先执行门闸；若输出占用进程则等待，不能另起构建：

```bash
ps -ewwo pid,comm,args | awk '$2 == "java" && /gradle-wrapper[.]jar|GradleWrapperMain/ { print }'
"./gradlew" --no-daemon --console=plain \
  -PkizunaCodeDir="/home/kiz/Code" \
  -Pkizuna.dir.bs3d="/home/kiz/Code-gear/backpack-and-storage-3D" \
  -PkpaStandalone test build
```

真实客户端测试先串行运行本仓 GameTest，生成隔离世界，复制世界后进入客户端。`gearTestEula` 指向本机已有的已接受 EULA 文件，测试不会替用户新接受协议。每次选一个新 `gearTestRun` 名保留历史日志和世界，不清理旧目录。

```bash
env -u DISPLAY -u WAYLAND_DISPLAY LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  xvfb-run -a -s "-screen 0 1280x960x24" \
  "./gradlew" --no-daemon --console=plain \
  -PkizunaCodeDir="/home/kiz/Code" \
  -Pkizuna.dir.bs3d="/home/kiz/Code-gear/backpack-and-storage-3D" \
  -PkpaStandalone \
  -PgearTestEula="/home/kiz/Code/Kizuna-Players-Animation/build/gametest/eula.txt" \
  -PgearTestRun=pa-review runClientTest
```

第二次运行添加 `-PgearWithoutPa` 并换运行名，物理移除运行时 PA；测试同时断言 Fabric 不含 PA 且 PA 类无法加载。编译仍使用 PA 的公开接口。两轮不能并行运行。

报告位于 `build/clienttest/<run>/junit.txt`、`result.txt`，截图为每件 REST/BENT × 正/侧/背三视角，共 28 张、84 视图。必须同时满足 Gradle 退出 0、JUnit 全过、成功标记及截图检查；单独有截图或标记不算通过。

G3 的披风链重建与物理、G4 的跨关节蒙皮/细臂精调/游泳鞘翅/完整第一人称及联网实机验收尚未交付。


## 防御与负重

WarHelm 为铁头盔 2 点、WarChest 为铁胸甲 6 点、WarLegs（含靴）为腿脚 7 点、
WarSuit 四区共 15 点，韧性随原版铁材质为 0；WarCloak 和其余外观件防御为 0。
属性由服务端真实穿戴栈推导，同一区多个模型段不重复叠加；穿脱、重连和死亡复活重算。
WarChest 保留既有 War 套资源，本轮未新增单件类型，也未新增耐久、附魔或套装效果。

负重需安装对应版本 Weights；Human 运动负荷由 Meridian Burden 消费 Weights 的公开读数。
Gear 生产包不硬依赖这两个附属。现有系数不变。五件 Bong 容器采用原定义克数，
Gear 穿搭、其余容器／腰带和原版盔甲缺少认可自重，暂沿用既有数据包或默认 100 g 回退；
这不是最终平衡值，完整缺项见 Weights Wiki 的「穿搭与容器重量来源」。

## 模型穿脱交互验证

`runClientTest` 增加 `-PgearModelEquipmentTest` 后验证独立创造栏、全部 Gear 与 BS3D 装备的模型拖入、右键卸装、重复点击、完整列表滚动、满背包拒绝、工作台嵌入输入，以及 Vanilla Addon 的 E 页、箱子页与创造库存页。Vanilla 仅作为客户端测试依赖，不进入 Gear 生产依赖。使用新的 `gearTestRun`，截图、`equipment-checks.txt` 和 `result.txt` 保存在同目录；原有骨骼渲染测试入口不变。所有 Gradle 调用外层须持有 `/tmp/kizuna-gradle.lock` 的阻塞 `flock`，拿锁后检查全机 wrapper 进程，调用使用 `--no-daemon`。
