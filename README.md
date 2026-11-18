# 灵感边框 2.0.13

离线原生 Android 照片边框与水印编辑器。Kotlin / Jetpack Compose；界面采用液态玻璃风格的浮动工具栏与面板，原图通过 URI 读取，照片在设备上处理。应用关闭了系统云备份，无账号和广告。包名 `com.parker.imageborder`。

品牌、图标与设置页对齐优化见 `docs/BRAND-2.0.9.md`。英文长标签、窄屏及大字体修复见 `docs/ENGLISH-2.0.8.md`。独立设置页与原生中英文本地化见 `docs/SETTINGS-2.0.7.md`。按字数的标点与英文排满修正见 `docs/TEXT-2.0.6.md`；正文行距与自动字号修正见 `docs/TEXT-2.0.5.md`。22 项界面审查与优化见 `docs/UI-AUDIT-2.0.4.md`；原生相册、权限和真实照片参数核对见 `docs/GALLERY-2.0.3.md`。

本轮正文断行与预览清晰度修复及真机结果见 [2.0.10 记录](docs/TEXT-PREVIEW-2.0.10.md)。

本轮精确调整与输入交互的 21 项检查及修复见 [2.0.11 记录](docs/INTERACTION-2.0.11.md)。

不同比例照片的默认留白、参数分组与相对字号、正文/水印的分辨率一致性，以及焦距输入修正见 [2.0.12 记录](docs/LAYOUT-2.0.12.md)。

参数留白叠加修复，以及独立位置、对齐、分栏控制见 [2.0.13 记录](docs/SPACING-2.0.13.md)。

## 原生编辑器

- 七组工具：模板、画幅、边框、文字、摄影参数、水印、调色。
- 8 个模板家族、32 个原创模板；支持横、竖、方图，多比例画布与完整容纳照片。
- 独立四边留白、底栏、圆角、描边、投影、照片配色、实际烘焙到输出的模糊玻璃背景。
- 自动 EXIF、逐字段手动覆盖与隐藏，实际/等效焦距分开显示，默认不读取 GPS；多图不冒用某一张的拍摄信息。
- 多个文字/Logo 图层、署名和平铺、透明度、旋转、描边、阴影、底色、位置锁定，本地字体和受限 SVG 导入。
- 独立图下正文：五种对齐、字体、字号、颜色、底色、行距、段间距、内距、图文间距与宽度；按正文实际高度扩展画布，清空后移除区域。
- 裁剪、旋转、镜像和基础调色，26 种风格及强度；含经典负片·青、经典正片、怀旧负片、海岸青橙、漂白电影等原创风格。创意调色使用 SDR，切换时提示。
- 独立设置页，中文/English/跟随系统，主原生流程的模板、滤镜、参数与错误文案本地化。
- Room 草稿/预设/导出任务、DataStore 偏好、100 步撤销重做、素材失效重新关联、预设包本地备份。
- 最多 100 张的串行导出；进度、暂停、恢复、失败重试和已完成项去重；九宫格与横向轮播切图。
- 单张/多图与文字图共用场景及原生渲染。预览使用小图，SDR 导出重新区域解码原图并分条编码。

界面设计依据与平台适配见 `docs/DESIGN-2.0.md`。文字和滑杆使用持续预览队列，快速操作时合并过时请求，停手后恢复精细预览；输入时隐藏底部缩略图及工具栏，为照片和键盘留出空间。HDR 预览直接显示单个浮点场景，导出另行重新编码增益图。

## 导出

“保持照片像素”向外添加边框；“兼容分享”提供 1280/1920/2560/3840 四档，默认 2560、JPEG 95、sRGB；自定义开放格式、质量、色域与尺寸控制。尺寸均不默认放大小图。JPEG 使用 libjpeg-turbo 4:4:4，PNG 使用 libpng。支持 sRGB、Display P3 和 Adobe RGB 的矩阵/TRC 色彩流程，按实际输出色彩空间生成 ICC。

Android 14+ 的 Ultra HDR JPEG 路径通过系统同一解码器取得 SDR 基图与增益图，按增益图参数重建浮点 HDR，避免双解码路径的画面几何差异。合成后的 SDR 基图与 HDR 目标图由 libultrahdr 重新编码增益图。HDR 另设内存预算，超限会明确报错，需用户选择较小输出或 SDR；不复制旧增益图。HDR 当前支持边框、参数、文字/Logo、裁剪和旋转，调色以 SDR 进行，HDR 切图暂不开放。

原图只读；默认保留安全摄影参数，移除 GPS 和设备序列号。画面手动填写的参数不冒充原始 EXIF。输出保存在相册 `Pictures/文字拼图`。

## 保留的 1.1 网页

`index.html` 和 `src/` 是保留的离线网页工具，通过“设置 → 1.1 旧版工具”打开，目前仅支持中文。网页仍保留原有图文拼接与文字水印功能，其导出限制仍为 1200 万像素；这不限制新的原生 SDR 渲染器。

本机留档包含 `artifacts/backup-1.1/` 中的 1.1 源码和 APK。给已有安装覆盖升级时需要保留原签名，构建脚本使用 `.tooling/android-user/debug.keystore`。该签名用于个人安装。

## 在 Mac 上构建

无需 Android Studio 或模拟器。需要 Node.js 22+、JDK 21、SDK Platform 36、Build Tools 35、NDK 28.2.13676358、CMake 3.22.1。工具和依赖集中在 `.tooling/`，不修改系统 Java。

```sh
npm ci
npm run android:build          # 构建网页并同步保留的旧版入口，再构建原生 APK
npm run android:native         # 原生调试版，首次构建时自动同步网页资源
bash scripts/android.sh release  # 精简版 APK，同一签名
npm run android:install
npm run android:test-build
```

NDK 构建从固定版本/校验值下载 libjpeg-turbo、libpng、libultrahdr；初次构建需要网络，安装后的应用离线使用。

## 真机验收

历史验收设备为 Redmi K90 Pro Max / Android 16。常规测试使用自生成照片和 [CC0 排版样张](docs/TEST-PHOTOS.md)，创建的相册测试项在测试结束后清理。

```sh
adb -d install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument -w -e class com.parker.imageborder.editor.EditorInstrumentedTest,com.parker.imageborder.editor.EditorUiInstrumentedTest,com.parker.imageborder.editor.RealtimePreviewTest,com.parker.imageborder.editor.EditorRegressionTest,com.parker.imageborder.editor.EditorFlowInstrumentedTest com.parker.imageborder.test/androidx.test.runner.AndroidJUnitRunner
```

真实 HDR 验收需要先在应用中选中照片并保存草稿，再显式指定测试类并传入 `-e useSelectedPhoto true`。此参数适用于 `RealPhotoAcceptanceTest` 和 `HdrAlignmentDiagnosticTest`。历史完整验收见 [2.0 验证记录](docs/VERIFICATION-2.0.md)，最新真机记录见 [2.0.13 验证记录](docs/SPACING-2.0.13.md)。

## 项目位置与空间

- 原生代码：`android/app/src/main/java/com/parker/imageborder/editor/`
- 模板数据：`android/app/src/main/assets/templates.json`
- 分条编码与 HDR：`android/app/src/main/cpp/`
- 编解码依赖许可：`android/app/src/main/assets/THIRD_PARTY_NOTICES.txt`
- 可安装产物：`artifacts/灵感边框-2.0.13.apk`（精简包）或 `artifacts/灵感边框-测试版.apk`；旧版本留作回退快照。

验收文档中的 `artifacts/` 和 `.tooling/` 路径指向当时开发机的本地留档，包括截图、日志和安装包。

2026-10-26 的开发工具及缓存约 6.4 GB，另有编译目录。`.tooling/downloads/` 安装包、Gradle/NDK 编译缓存可在保留签名后清理；删除构建目录不影响手机中安装的应用。应用包与工程工具占用需分开计算。
