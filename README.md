# 网页播放器（Android）

粘贴网页链接，自动嗅探页面视频（HLS/MP4/WebM），优先用原生播放器播放；提取失败时在内置网页中播放。支持历史、收藏、续播、倍速、清晰度、画中画与手势控制。

## 功能边界
- 仅 Android，Android 8.0（API 26）及以上
- 不提供下载、系统分享入口、DRM 破解、站点专属解析
- 不绕过会员/DRM 内容；仅用于你有权观看的内容
- 数据全部保存在本机，不联网同步

## 构建
1. 安装 JDK 17 与 Android SDK 35、build-tools 35.0.0
2. 在项目根目录创建 `local.properties`：
   ```
   sdk.dir=/Users/jiang/Library/Android/sdk
   ```
3. 构建：
   ```bash
   JAVA_HOME=/Users/jiang/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew :app:assembleDebug
   ```
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装
- USB 调试：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 或把 APK 传到手机，允许“安装未知应用”后打开安装

## 使用
1. 粘贴网页链接，点“打开网页”
2. 页面加载后点右上角“媒体(N)”
3. 选择视频条目用原生播放器播放；无条目时直接用网页内播放
4. 播放页可收藏、切换清晰度/倍速、画中画；历史会自动记录进度

## 常见问题
- **页面能打开但没嗅到视频**：部分站点使用 blob/MSE 或强 DRM，无法提取，请直接网页播放
- **播放 403/404**：地址令牌过期，点“回网页播放”让页面刷新后重新嗅探
- **网站提示浏览器不支持**：尝试菜单里的“桌面版网页”
- **无法播放但网页能播**：设备编码不支持（如部分 HEVC/AV1），用网页播放

## 本地验收
见 `docs/测试清单.md`；测试页在 `testserver/`，用 `python3 -m http.server 8765 --directory testserver` 启动。
