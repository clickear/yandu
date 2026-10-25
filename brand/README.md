# 言渡 · YANDU 品牌资产

<img src="svg/logo-horizontal.svg" width="360" alt="言渡 YANDU 横版标志" />

本目录中的图片与 SVG **直接存放在 Git 仓库**，无需图床、LFS、下载脚本或外部字体。可打开 [preview.html](preview.html) 查看彩色、单色和深色背景效果。

## 来源与当前版本

用户在关联对话中确认了「流动 Y」紫橙方向。原对话图片没有通过读取工具返回，因此当前图形是根据文字方向重建的版本，并非已核对过的旧图复刻。

- `concepts/flowing-y-generated.png`：本轮图像工具生成的概念参考，保留原始透明 PNG。
- `source/mark.svg`：整理后的生产矢量母版。
- `source/wordmark.svg`：已转为路径的「言渡 YANDU」字标。
- `svg/`、`png/`、`icons/`、`social/`：从矢量母版统一生成的交付文件。

概念图与矢量母版不是完全相同的图形。产品和导出统一使用矢量母版，不混用概念图作为应用图标。

## 选用文件

| 场景 | 文件 | 规格 |
| --- | --- | --- |
| 导航、文档页头 | [logo-horizontal.svg](svg/logo-horizontal.svg) | 460×112，透明，文字转曲 |
| 社交头像、独立标志 | [logo-mark.svg](svg/logo-mark.svg) | 256×256，透明 |
| 方形品牌组合 | [logo-stacked.svg](svg/logo-stacked.svg) | 512×512，透明 |
| 深色背景彩色图形 + 白字 | [logo-horizontal-white.svg](svg/logo-horizontal-white.svg) | 透明 |
| 单色印刷 | [logo-horizontal-mono.svg](svg/logo-horizontal-mono.svg)、[logo-mark-mono.svg](svg/logo-mark-mono.svg) | 深墨蓝 |
| 纯白反白 | [logo-horizontal-reversed.svg](svg/logo-horizontal-reversed.svg)、[logo-mark-white.svg](svg/logo-mark-white.svg) | 透明底纯白 |
| 浏览器 favicon | [favicon.ico](icons/favicon.ico)、[logo-mark.svg](svg/logo-mark.svg) | ICO 含 16/32/48 三帧 |
| Apple Touch | [apple-touch-icon.png](icons/apple-touch-icon.png) | 180×180，不透明浅色底 |
| Web 应用图标 | [android-chrome-192.png](icons/android-chrome-192.png)、[android-chrome-512.png](icons/android-chrome-512.png) | 192 / 512 |
| Web maskable | [maskable-icon-512.png](icons/maskable-icon-512.png) | 512×512，保留中央安全区 |
| 社交分享 | [og-image-1200x630.png](social/og-image-1200x630.png) | 1200×630，同时提供 SVG |
| GitHub 仓库封面 | [github-cover-1280x640.png](social/github-cover-1280x640.png) | 1280×640，同时提供 SVG |

透明 PNG 均位于 [png/](png/)：

- 图标：16、24、32、48、64、128、256、512、1024 像素正方形。
- 横版：宽 320、640、1280、2560 像素，按母版比例缩放。
- 竖版：512、1024、2048 像素正方形。

Web manifest 仅提供应用名称和图标元数据，不代表已实现离线服务、原生安装包或 App Store 资产。

## 配色与使用

| 名称 | 色值 | 用途 |
| --- | --- | --- |
| 电光紫 | `#7C3AED` | 主标志、重点操作 |
| 珊瑚橙 | `#FF7849` | 交织色块、活力点缀 |
| 深墨蓝 | `#24233A` | 字标、正文、单色版 |
| 浅紫底 | `#F7F1FF` | 应用图标底色 |

保留至少图标宽度 1/8 的净空；等比缩放，不拉伸、旋转、添加阴影或改动交织关系。彩色版用于浅色底，深色底优先白字版；小图标不带字标。排版文字已转曲，接收者无需安装字体；业务界面仍使用系统字体以兼顾速度与可读性。

## 重建与校验

仓库根目录执行：

```sh
npm ci
npm run brand:generate
npm run brand:check
```

生成脚本只读取已提交的矢量母版，Sharp 完成格式和尺寸导出，随后同步 `public/`。它不会重新调用图像生成服务，也不会覆盖概念图。

`outline-wordmark.py` 是可选维护工具，只有需要修改字标轮廓时才使用，依赖 Python fontTools 和系统 Noto Sans CJK / DejaVu Sans 字体。常规重建无需这些工具。字标使用 Noto Sans CJK SC Bold 与 DejaVu Sans Bold；许可全文见 `source/FONT-LICENSE-*.txt`。未设置仓库整体开源许可证，字体许可仅适用于相应字体与轮廓，不替代项目许可。
