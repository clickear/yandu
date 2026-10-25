<p align="center"><img src="brand/svg/logo-horizontal.svg" width="340" alt="言渡 YANDU" /></p>

<p align="center">让每一种语言，准确抵达。</p>

言渡是一个翻译管理平台。本仓库已初始化为 **React + TypeScript + Vite 的本地工作台原型**，使用已确认的「流动 Y」紫橙品牌方向。

## 执行目标

后续开发、评审与商业试用验收统一参考 [产品目标与交付标准](GOAL.md)。当前仍为本地原型，服务端实施先细化 spec 003。

## 运行

需要 Node.js **22.12+**（云环境验证版本为 24.19.0）和 npm。

```sh
npm ci
npm run dev
```

默认端口为 5173；`npm run dev -- --host 127.0.0.1` 可限制为仅本机访问。云环境如遇 npm 默认缓存目录不可写，在命令前加 `NPM_CONFIG_CACHE=/workspace/.cache/npm`。无需 API Key、数据库或后端服务。

## 已实现

- 创建项目、切换项目、选择源语言及添加目标语言。
- 添加唯一文案键；按目标语言编辑译文、标记审核、统计进度。
- 按键名、源文案、译文搜索，按未翻译 / 待审核 / 已审核筛选。
- 导入源语言或目标语言的扁平字符串 JSON；按同名键合并，不删除其他条目。
- 导出当前目标语言 JSON，未翻译条目导出为空字符串。
- 浏览器 `localStorage` 自动保存；损坏或不兼容的数据不会自动覆盖。
- 中英日示例数据、桌面和移动布局、键盘可操作弹窗。

这是单浏览器原型：数据不跨设备同步，不包含登录、后端、多人协作、机器翻译或正式发布服务。JSON 仅包含文案，不包含审核状态；它不是完整工作空间备份。源文案可通过源语言 JSON 更新；重命名、删除、完整备份等见后续计划。

导入格式：

```json
{
  "home.title": "Welcome to YANDU",
  "common.save": "Save changes"
}
```

支持最大 1 MB、每项目最多 10,000 个键的 JSON 导入。暂不支持嵌套对象、数组、ICU 复数语法校验。导入会先校验整份文件，再一次性合并；更新源文案会使已有译文回到待审核，更新目标译文也会清除该译文的审核状态。未改变的内容保留原审核状态。

## 验证

```sh
npm run typecheck
npm test
npm run build
npm run test:e2e
npm run brand:check
npm run format:check
```

E2E 默认优先使用云环境的 `/usr/bin/chromium`，也可设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`。其他机器首次运行可用 `npx playwright install chromium` 安装浏览器（需要允许其官方下载域名）。测试自动启动开发服务；CI 会要求独占 5173 端口。

## 规格、计划与品牌

| 规格                                                       | 对应实施计划                                       |
| ---------------------------------------------------------- | -------------------------------------------------- |
| [001 翻译工作台原型](specs/001-translation-workspace.md)   | [001 原型实施](plans/001-translation-workspace.md) |
| [002 品牌与资产](specs/002-brand-assets.md)                | [002 品牌实施](plans/002-brand-assets.md)          |
| [003 服务端与协作草案](specs/003-backend-collaboration.md) | [003 后续实施](plans/003-backend-collaboration.md) |

原对话能读取的内容包含品牌方向，但未包含功能 spec 和原 Logo 图片。本次工作台范围由用户确认采用默认方案；功能规格据此新建。当前 Logo 是依据文字方向重建的矢量版本，原对话图片的外观尚未逐像素核对。

品牌文件**直接保存在 Git 仓库**，不使用外部图床或 Git LFS：

- [品牌资产索引与使用说明](brand/README.md)
- [独立图标 SVG](brand/svg/logo-mark.svg)、[横版组合 SVG](brand/svg/logo-horizontal.svg)、[竖版组合 SVG](brand/svg/logo-stacked.svg)
- [PNG](brand/png/)、[favicon 与应用图标](brand/icons/)、[社交分享与 GitHub 封面](brand/social/)
- [品牌预览页](brand/preview.html)，下载后可直接用浏览器打开

```sh
npm run brand:generate
```

该命令从提交的矢量母版重建所有尺寸并同步 `public/`，不需要原图、在线字体或图像服务。字体已转为路径，相关许可见 `brand/source/`。
