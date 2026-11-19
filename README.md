# Modern Skis · 现代滑雪板

给 Minecraft 加一对滑雪板与滑雪杖的 Fabric 模组。当前为 1.0.0 测试版。

## 内容

| 物品 | ID | 说明 |
|---|---|---|
| 现代滑雪板 | `skis:modern_ski` | 脚部装备，穿上后腿部姿态与脚上的雪板模型同步渲染 |
| 滑雪杖 | `skis:ski_pole` | 撑杖推进 |

- **起滑**：右键起滑冲刺，撑杖推进
- **姿势**：滑行时双腿外分，两只雪板随之分开并保持平行
- **音画**：脚步音效、落地音效与镜头晃动针对滑行状态单独处理，避免滑行时仍播放行走动画与原版走路摆动

## 合成

```
现代滑雪板 modern_ski            滑雪杖 ski_pole ×2
  I I     I = 铁锭                 I     I = 铁粒
  P P P   P = 任意木板             S     S = 木棍
                                   S
```

## 环境要求

- Minecraft **26.2**
- Fabric Loader **≥ 0.19.5**
- Fabric API
- Java **≥ 25**

## 安装

1. 安装 Fabric Loader 与 Fabric API
2. 把构建出的 jar 放进 `.minecraft/mods/`
3. 启动游戏

## 构建

```bash
./gradlew build
# 产物：build/libs/skis-1.0.0.jar
```

## 贴图生成

贴图与模型 UV 全部由脚本生成，不依赖外部素材：

```bash
python tools/gen_assets.py
```

## 许可证

[MIT](LICENSE)
