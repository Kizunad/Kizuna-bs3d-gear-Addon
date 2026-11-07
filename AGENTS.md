# 仓库约定

- 穿搭资产、PA 刚性绑定和原版回退由本仓负责；库存、层冲突、保存和同步复用 BS3D。
- 文档、交流和说明性注释使用简体中文；Java 遵守 `/home/kiz/.codex/AGENTS.md` 的代码形状规则。
- 保留所有已有改动；未经明确授权不提交、不推送、不创建 tag。
- Gradle 前检查全机 `gradle-wrapper.jar|GradleWrapperMain` 门闸，一次只运行一个，并使用 `--no-daemon`。
- Bong 输入固定提交与 blob SHA；生成资源必须能由导入脚本重现，不手改生成模型。
- 单测验证坐标与数据契约；实际渲染须运行 clientTest 并查看截图，不能用编译代替。
- G1 仅刚性绑定；War 防御和负重属于 G2，动画物理属于 G3。
