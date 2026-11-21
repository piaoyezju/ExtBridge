# Changelog / 更新日志

本项目版本号与安装包后缀同步（见 `helper/build.gradle.kts` 及 GitHub Releases）。

---

## Unreleased — 激活方式改为 Shizuku（替代无线调试配对）

### ✨ Shizuku 一键激活
- 新增 Shizuku 依赖与清单声明：`dev.rikka.shizuku:api / aidl / provider:13.1.5`，并在 `AndroidManifest.xml` 中声明受 `INTERACT_ACROSS_USERS_FULL` 保护的 `rikka.shizuku.ShizukuProvider`，`<queries>` 增加 Shizuku 包可见性。
- 新增 `ShizukuBootstrap.java`：Shizuku 服务/授权状态探测、授权请求（`OnRequestPermissionResultListener` 回调）、`IShizukuService#newProcess` 特权 Shell 执行（分别捕获 stdout / stderr 与退出码，带超时守护）、打开 Shizuku 应用或下载页。
- `AdbBootstrap` 新增 `shizukuBootstrapAsync()`：复用原有「SHA-256 校验 → `mktemp (O_EXCL)` 原子替换 → `appops`/`pm grant`/`deviceidle` 授权 → `u:r:shell:s0` 降权启动」脚本，直接以 Shizuku 的 shell(uid 2000)/root 身份拉起守护进程，**不再需要开启无线调试、不再需要 6 位配对码与 `adb connect`**。
- 激活抽屉首页改为 **「Shizuku 激活」**：实时状态提示、`授权 Shizuku`、`Shizuku 一键激活服务`、`打开 Shizuku`、`启动时自动 Shizuku 激活`（默认开启）；移除 mDNS 配对端口、6 位配对码、连接端口输入与通知栏填码入口，`Root 激活` 与 `电脑 ADB` 两种方式完整保留。
- 启动/回到前台时自动刷新 Shizuku 状态；打开应用若已授权 Shizuku 则优先静默激活（失败回退到激活抽屉），Root 自动激活逻辑保留为次级兜底。
- 旧版内置 `libadb.so` 无线调试配对实现（`AdbBootstrap` 配对/mDNS、`AdbPairingReceiver` 通知栏填码）**已彻底删除**：移除 `helper/jniLibs/arm64-v8a/libadb.so`、`AdbPairingReceiver`、`AdbBootstrap` 内的 mDNS 发现/`adb pair`/`adb connect`/私有 adb socket/配对通知全部代码，剔除 `CHANGE_WIFI_MULTICAST_STATE` 权限与 `ndk.abiFilters` / `jniLibs` 打包配置，APK 体积由 5.56 MB 降至 3.36 MB。
- **签名**：本地开发改为读取根目录 `hzgndz.keystore`（JKS，别名 `testalias`）并支持 `local.properties` 的 `keystore.file/keystore.alias/keystore.type/keystore.pass` 覆盖（`local.properties` 与 `*.keystore` 均已被 `.gitignore` 忽略，口令不进版本库）；本地自定义证书不再强制匹配官方指纹，CI Tag 发布仍强制官方密钥。

### 🧩 折叠屏（Galaxy Z Flip5）适配
- **外屏是独立逻辑显示**：三星折叠机的覆盖屏/副屏是 `displayId=1`（748×720、`FLAG_PRESENTATION`、`INSTALL_ORIENTATION_90`），不是 display 0 的另一种模式。新增 `pickActiveBuiltinDisplayId()`：内屏亮用 0，折叠时自动切到亮着的那块内置屏。
- **镜像源不再硬编码 display 0**：`mirrorDisplay`、镜像几何（`readDisplayRealSize(displayId)`）、外接鼠标光标 `getInputManager().getCursorPosition(displayId)`、`moveDisplayToTop` 全部跟随 `sMirrorSrcId`；折叠/展开时通过 `DisplayListener` + tick 检测到活动内置屏变化会自动重建 mirror 层（新增 `EXTBRIDGE_MIRROR_SRC id=` / `_SWITCH` / `_REBUILD` 日志）。
- **App 侧不再硬编码 `setLaunchDisplayId(0)`**：`MainActivity` / `TouchpadActivity` / `BridgeService`（通知点击、主控台、触摸板）改为「当前亮着的内置屏」，且不再在内屏/外屏之间来回搬自己（之前会导致在外屏点开 App 一闪就退）。
- **移除隐藏 API 依赖**：`Display#getType()` 在 App 进程反射会被拦截，改为按显示 ID 判定内置屏（0~5 为内置，扩展屏/虚拟屏 ID 更靠后）；daemon 侧同样以 ID 规则兜底并排除 `TYPE_EXTERNAL`。
- **OEM 忽略 `setLaunchDisplayId` 的补救**：三星在 shell 发起的启动里会静默把任务丢到当前活动屏。新增 `enforceLaunchedTaskOnVd()`（启动后 80/160/320/640/1200/2000ms 连续校验并用 `moveRootTaskToDisplay` 拉回虚拟屏）、DeX 稳态每 1.5s 兜底检查、释放虚拟屏前先清理残留 ExtBridge 桌面任务（否则会被系统迁移到手机屏抢焦点）。
- **已知限制（系统行为，非本应用）**：三星在 HDMI 热插拔瞬间会把 `device_state` 从 `CLOSED` override 成 `OPENED`（`mBaseState=CLOSED` / `mOverrideState=OPENED`，requester 为 system_server），于是活动屏切到内屏、外屏熄灭；本应用停止运行时同样复现。规避方式：插线后展开再折叠一次即可恢复外屏。

### ✅ 验证
- 真机（Galaxy Z Flip5 / Android 16 + Shizuku 13.1.5 shell 模式）实测：`授权 Shizuku` → `Shizuku 一键激活服务` → 守护进程 `app_process` 成功以 `u:r:shell:s0` 拉起，主控台顶部显示 **「核心服务在线 · 待插入显示器」**。
- 修复真机暴露的三个问题：
  1. **守护进程信任锚**：`bridge/ExtBridge.java` 的 `EXPECTED_HELPER_CERT_SHA256` 单值校验在本地签名包上导致 Binder 握手全被拒（日志 `EXTBRIDGE_SECURITY_REJECT untrusted ... cert=cf854b8d...`）；改为 `TRUSTED_HELPER_CERT_SHA256[]` 白名单，同时保留官方发布证书与本地 `hzgndz.keystore` 证书。
  2. **bootstrap 脚本自杀**：原 `pkill -f '^(/.*/)?app_process.*ExtBridge'` 的正则 `(/.*)?` 会把自己所在的 `sh -c "..."` 命令行（内含 `app_process ... ExtBridge` 文本）一起匹配，导致脚本在拉起前被 SIGTERM（日志 `execShell: code=143 outLen=0`，并残留大量 `extbridge.XXXXXX` 临时 jar）；改为锚定 `^(/system/bin/)?app_process .*com\.extbridge\.daemon\.ExtBridge`，并在脚本开头清扫历史临时 jar。
  3. **诊断能力**：Shizuku 路径的 bootstrap 脚本开启 `set -x` 并重定向到 `/data/local/tmp/extbridge_boot.trace`，失败时自动附带 `id / getenforce / extbridge.log` 探测结果到弹窗与 logcat。
- 构建脚本：`build_v3.ps1` 改为优先选用 JDK 21+（Android Studio JBR）、`local.properties` 以无 BOM 方式写入、保留 `keystore.*` 覆盖项，并在本地自定义密钥场景下跳过官方指纹校验。
- `:helper:assembleDebug` 与 `:helper:assembleRelease`（R8 + Lint Vital）均编译通过；release 混淆后 `moe.shizuku.server.IShizukuService` 等 AIDL 描述符与 `rikka.shizuku.ShizukuProvider` 均保留；产物已用 `hzgndz.keystore` V3 签名（`CN=sunfeng / O=hzgndz`，SHA-256 `cf854b8d...5bc3`）。

---

## v3.5.3 (`versionCode: 25`) — 2026-11-18

### ✨ 全新界面重构：Xiaomi HyperOS 原生风格（`Jetpack Compose + Miuix 0.9.4`）、舞台过渡动画与周边联动
- **主控台全面重构 (`MainActivity.kt` + `Miuix 0.9.4`)**
  - 引入 **手机竖屏（可折叠大标题 `TopAppBar` + 底部四栏 `NavigationBar`）** 与 **平板横屏（左侧 `NavigationRail` + 双栏工作台）** 自适应响应式骨架，支持 **跟随系统 / 澎湃浅色 / 澎湃深色** 三态外观实时切换。
  - 重组信息层级为 `控制台`、`显示与边栏`、`应用规则`、`设置` 四大清晰板块：首页精简为连接状态卡 + 输出模式分段胶囊 + `2×2` 澎湃快捷控制磁贴（新增首页直达 **一键黑屏** 开关）；权限提示仅在缺失时按需展示；连接诊断、三模激活引导、EDID 物理信号档位、系统 `AppWidget` 绑定及 14 款镜像卡片管理全部收纳进丝滑的 Miuix `OverlayBottomSheet` / `OverlayDialog`。
  - **分应用显示规则独立成页**：提供实时搜索、应用图标展示、已自定义应用置顶及一键下拉切换兼容策略；原生镜像布局切换升级为 **2×2 可视化微缩显示器示意图卡片**；DeX DPI 调节升级为关键刻度吸附滑块并实时换算 `swDp`。
- **HyperOS 2.0 级窗口舞台过渡动画引擎 (`StageTransitionView.java` + `ExtBridge.java`)**
  - 新增守护进程跨进程零拷贝 GPU `TaskSnapshot (HardwareBuffer)` 快照通道（兼容 Android 17 `ITaskSnapshotManager` 并带 `FLAG_SECURE` / 冷启动毛玻璃应用卡兜底）；实现 Dock 图标点击退回后台（窗口微弧收缩形变为图标 + 弹性回弹）、后台应用唤起展开、自由分屏进入/退出、`3:7 ~ 7:3` 比例切换双窗同步形变及左右/上下互换立体交叉动画。
- **全周边界面 HyperOS 视觉联动（零 Emoji · 线性矢量图标统一）**
  - **大触摸板 (`TouchpadActivity.kt`)**：同步重构为 `Compose + Miuix` 架构，并修复虚拟触摸板「右键 / 返回」与双指轻触在外接屏无法触发 `KEYCODE_BACK` 的焦点与按键分发缺陷（智能区分桌面/Dock 上下文菜单、浮窗收起与应用内跨屏返回）。
  - **DeX Dock、澎湃控制中心浮窗、扩展桌面启动器、副屏直控与镜像融合边栏 (`HyperUi.java` + `BridgeService.java` + `LauncherActivity.java` + `SideControlActivity.java`)**：新增抗锯齿线性矢量图标绘制引擎 `HyperUi`，全面清除浮窗、Dock、分屏气泡、启动器顶栏及 14 款镜像边栏信息卡中的 Emoji 符号，统一采用澎湃标志蓝 (`#0D84FF`) 激活磁贴与 `22dp` 毛玻璃圆角卡片风格。

### 🐛 全链路恶性隐患治理、镜像副屏防串屏与 IPC 安全加固（`v3.5.1` ~ `v3.5.3` 合并修复）
- **分屏退出半屏黑屏卡死根治**：修复 Android 15~17 / HyperOS 2.0 下 `MULTI_WINDOW (6)` 直接切回 `FULLSCREEN (1)` 残留分屏裁剪/偏移坐标（如 `7:3` 退出后左侧黑屏遮挡点击）的缺陷，全路径先 `resizeTask(fullBounds)` 再切回 `FULLSCREEN (1)`。
- **镜像模式副屏偶发渲染串屏（变成应用界面一部分）根治**：新增主屏镜像物理像素硬裁剪容器 `ExtMir0Clip`（配 `ExtMir0Bg` 纯黑底衬），单侧副屏模式下将 `ExtMirrorSide` 提升至 `layer 2` 配合精确边栏 `setCrop` 硬件裁剪（HWC `DEVICE` 直通）；副屏窗口升级为 `PixelFormat.OPAQUE` 实心背景与无空窗期原子换窗（保持 `AppWidgetHost` 持续监听），配合 `IDisplayManager` 无缓存主屏横竖屏追踪与 `35ms / 110ms / 280ms` 副屏误入任务自动迁回主屏，彻底杜绝串屏。
- **镜像副屏点击第三方小组件（如网易云音乐）指针跳出副屏与原点偏移修复**：通过 `0x1040B`（`STEAL_TOP_FOCUS_DISABLED`）、`mTopFocusedDisplayId=0` 焦点回正、WMS 焦点脉冲无感重获 `PointerCapture` 及非越界释放零强制位移彻底消除跳针。
- **全链路 IPC 鉴权、特权隔离、崩溃自愈与构建供应链深度加固（Codex + Claude 联合安全审计修复）**：导出 `ControlReceiver` 升级为纯签名级权限 `android.permission.INJECT_EVENTS` 并拆分非导出 `ControlReceiver$Internal`；移除 `BridgeService` 与 `LauncherActivity` 未鉴权 Binder Extra 注入，收敛至唯一 `BridgeProvider` 入口并实施 `IBridgeControl` `InterfaceToken` 校验与 `com.extbridge.helper` 官方 X.509 证书 SHA-256 动态绑定（信任模型限定为 `uid 0/1000/2000` 及通过官方证书校验的客户端，补充高危 Binder 动作包名与外接屏任务归属白名单）；守护进程强制清空附加组并校验降权 `uid/gid=2000`（失败即退），Root 与手动激活脚本先校验哈希/证书并降权至 `u:r:shell:s0` 后以 `mktemp` (`O_EXCL`) 拒绝符号链接原子替换，三端电脑 ADB 激活命令统一为零 `$` 跨平台管道；内置无线调试 ADB 改用应用私有 `0700` Unix Socket、校验本机 IP、全路径 `finally` 清理并支持自动关闭无线调试；`@com.extbridge.daemon.ctl` 双向校验 `SO_PEERCRED` 且取锁失败即退；启动前自动恢复残留键鼠绑定与孤儿亮度备份，退出重排为「原子备份读回校验恢复亮度 → 解绑输入 → 释放图层 → 还原分屏兼容」；CI 最小化 `contents: read` 权限、全量 Action 按 Commit SHA 固定、隔离签名口令与 `release` 作业，移除 V1 与 Debug 签名回退，新增 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 完整声明 `libadb.so` 来源、SHA-256 与开源许可证。
- **现代化构建工具链与 R8 瘦身**：升级至 Gradle `9.7.1` + AGP `9.4.1` + Kotlin `2.4.20` + JDK `21` + Compile SDK `37.0`，启用 R8 代码与资源压缩（整包约 `5.29 MB`），保持原有签名无缝覆盖升级。

---

## v3.4.2 (`versionCode: 21`) — 2026-11-18

### 🐛 关键兼容修复：小米平板工作台小窗劫持防护 & 21:9 带鱼屏原生适配
- **彻底解决开启「小米工作台模式 / 强制桌面模式」时扩展屏桌面与应用被强转为居中悬浮小窗 (`•••`) 及鼠标无法拖拽窗口的问题**
  - 在小米平板 6S Pro（HyperOS 3）等开启系统自带「工作台模式 (`miui_dkt_mode`)」或开发者选项「强制使用桌面模式 (`force_desktop_mode_on_external_displays`)」的设备上，系统会将默认 `WINDOWING_MODE_UNDEFINED (0)` 的可缩放窗口强行拦截为 `WINDOWING_MODE_FREEFORM (5)` 悬浮小窗，且小米原生小窗顶部 `•••` 仅绑定主屏触摸、外接鼠标无法拖动。
  - 现统一在 `makeExtLaunchOptionsForMode` 中为所有非分屏扩展桌面启动（含 `LauncherActivity` 及 `mode = 0 / 1 / 2` 应用）显式指定 `WINDOWING_MODE_FULLSCREEN (1)` 与空 `launchBounds`；`applyDisplayFullscreenPolicy` 始终保持 `VirtualDisplay` 容器默认模式为 `FULLSCREEN (1)`。
  - 修复 `enforceRunningExtTasksFullscreen` 通过 `configuration.windowConfiguration.getWindowingMode()` 精确识别窗口模式（原 `readIntField` 在 Android 11–16 恒回退为 `1`），对任何被系统偷偷切入 `FREEFORM (5)` 的扩展屏非分屏任务实时自动剥离 `alwaysOnTop`、恢复 `FULLSCREEN (1)` 并重同步 `SurfaceFlinger` 边界；Dock `⊞ 桌面` (`relaunchLauncher`) 同步接入全屏恢复闭环，并清理 `switchToTaskOnVd` 历史残留的 `mode == 3` 分支。
- **原生适配 `3440×1440` / `2560×1080` (21:9 带鱼屏) 与 EDID 最优物理模式智能识别**
  - 控制台快捷预设新增 **`3440×1440 (21:9 准4K)`** 与 **`2560×1080 (21:9 带鱼)`**。
  - 新增 `pickBestNativeEdidMode`（智能过滤 `3840×2160@30Hz` 等低刷 HDMI 兼容档位，优先挑选 `≥50Hz` 最大像素原生面板档位，如 `#128 3440×1440@60Hz`）：首次插入外接显示器时若 `3:2` 平板系统默认协商在 `2048×1536` / `2560×1600`，会自动尝试切换至显示器原生满幅宽屏档位。
  - 修复控制台 **「↺ 恢复推荐默认」** 硬编码 `2560×1440` 的问题，改为动态匹配当前外接显示器 EDID 最优原生分辨率并同步下发物理模式请求。

---

## v3.4.1 (`versionCode: 20`) — 2026-11-17

### ✨ 核心功能新增（v3.3.x → v3.4.1 功能汇总）
- **原生镜像模式全面重塑为「信息侧边栏 + 系统小组件工作台」**
  - **4 种画面与黑边排版形态**：支持关闭（`0`）、镜像右移 + 左侧合并宽侧边栏（`1`）、镜像左移 + 右侧合并宽侧边栏（`2`）与居中双翼对称侧边栏（`3`），支持按不同显示器分辨率与屏幕比例自动适配排版，避免黑边空间浪费。
  - **14 款可自由替换与排序的预设信息组件**：内置 `大数字时钟`、`电池温压详情`、`Wi-Fi 与信号`、`蓝牙与外设`、`音频通道与音量`（支持直接点按 `➖/🔇/➕`）、`媒体播放与切歌`（支持点按 `⏮/⏯/⏭`）、`日历与近期日程`、`内存与系统负载`、`实时网速监控`、`存储与亮度信息`、`专注番茄钟`、`快捷桌面便签`、`镜像快捷控制台` 与 `主屏最低亮度` 卡片，字体全面放大清晰易读。
  - **6 款主题配色 + 自定义颜色**：支持 `极夜深蓝`、`OLED纯黑`、`晨雾浅白`、`赛博紫青`、`翡翠墨绿`、`暖暮琥珀` 及自定义背景纯色，同步适配前景色高对比度。
  - **系统桌面小组件 (`AppWidget`) 接入与防拉伸保护**：支持左/右槽位绑定任意系统/第三方小组件，并提供 `自动原比例 / 方形 1:1 / 竖屏 3:4 / 宽屏 16:9 / 铺满槽位` 5 档高度比例锁定，解决窄栏中 AppWidget 异常拉伸变形问题。
- **外置鼠标外屏显示、自由跨屏 & 无键鼠「副屏直控」**
  - 修复原生镜像下外置鼠标仅显示在主屏而不在外接屏显示的问题，实现实体鼠标**从镜像区左右边缘无缝跨入黑边侧边栏**点按、滚轮与右键操作小组件，并修复边缘碰触时误弹出 ExtBridge 主界面的 Bug（通过边缘捕获边界保护及 `SideMouseCaptureActivity` 协同）。
  - 提供 **`0.9x 精细 / 1.4x 标准 / 1.9x 流畅 / 2.6x 极速 / 3.4x 飞快`** 五档副屏光标灵敏度与 DPI 密度自适应，兼顾低分与 4K 高分显示器手感。
  - 新增主屏 **`副屏直控` (`SideControlActivity`)** 并升级虚拟触控板直接点按小组件能力，无外置键鼠时在平板/手机屏即可手指触控操作镜像副屏所有组件与 AppWidget；同时保护平板主屏原有桌面布局不受破坏。
- **主屏一键黑屏 & 最低亮度省电双向闭环**
  - DeX 底部 Dock 快捷控制浮窗新增 **「🌙 一键黑屏 (平板最低亮度)」**，镜像侧边栏同步配备 **「🌙 主屏最低亮度」** 卡片：一键将平板/手机主屏背光压至硬件最低 `1` 并叠加防误触遮罩（支持 `NavAssistService` 无障碍层级兜底）。
  - 支持双击黑屏遮罩安全退出并精确还原进入前亮度，且在服务销毁、断开外接屏或停止退出时强制执行亮度恢复。

### 🛡️ 全量代码审查修复、性能与内存优化
- **Daemon (`ExtBridge.java`)**：
  - 为输入队列、光标追踪、分屏状态与亮度调节补充细粒度互斥锁（`sInputLock` / `sCursorLock` / `sSplitLock` / `sScreenDimLock`），并缓存 `SurfaceControl` / `Transaction` 反射句柄（统一由 `closeSurfaceTx` 释放）。
  - 修复特定条件下 `VirtualDisplay` 每 `1.5s` 循环重建（区分 `NATIVE_MIRROR_STOCK`、引入 `sSideVdAttempted` 保护、`physSizeChanged` 判断）；`startMirrorCursorPoller` 改为自适应退避轮询（`16ms → 50ms → 150ms`）与基于时间戳的边缘防抖。
  - 强化 `BridgeBinder` 调用方 UID ACL 鉴权 (`isTrustedCaller`)，限制 `runCmdOut` 256KB 输出与 3 秒超时；修复 Captured Mirror 鼠标右键/中键（`actionType 6/7`）与 `code 42` 重入捕获保护，并确保 `cleanupDisplay` 幂等恢复亮度。
- **Helper (`BridgeService.java` / 客户端 / 接收器 / 清单)**：
  - 将 `ActivityManager.MemoryInfo`、`TrafficStats`、`StatFs` 与 `SCREEN_BRIGHTNESS` 读取移出主线程 `refreshMirrorSideViews()`；`mPomoTickTask` 仅在计时运行期调度；图标 `LruCache` 使用 `cloneDrawable()`，预分配高频绘制视图中的 `RectF` / `Path`，`QuickTile` 复用 `GradientDrawable`。
  - 增加 `mDestroyed`、`mPollSeq` 序号守卫与 `safeBgExecute` / `safePost`；`detachExtOverlays(boolean keepBlackout)` 防止切层误撤黑屏；`BridgeProvider` 限制调用方 UID 白名单；`BridgeClient` 改用 `CopyOnWriteArraySet` 与 `linkToDeath` / `pingBinder()`；`MainActivity`、`TouchpadActivity` 与 `LauncherActivity` 在生命周期结束移除监听；`TouchpadActivity` 鼠标移动合并异步节流；`ControlReceiver` 使用 `goAsync()` 异步响应并在 `AndroidManifest.xml` 补齐 11 项 Action 声明。

### 📚 图文文档与发布构建
- 新增完整实机截图集 [`docs/images/`](docs/images/)（11 张实机全流程截图）、完整图文使用说明书 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md)、更新 [README.md](README.md) 与发布说明 [`docs/RELEASE_v3.4.1.md`](docs/RELEASE_v3.4.1.md)。

---

## v3.3.0 (`versionCode: 9`) — 2026-11-17

### ✨ 全新特性：DeX 自由分屏模式 & Dock 栏自动隐藏
- **DeX 独立桌面分屏模式（横屏左右 / 竖屏上下 + 自由调比例）**
  - 自动适配输出方向：当扩展屏为横屏（`virtW >= virtH`）时自动启用**左右分屏**；旋转 `90°/270°` 或竖屏分辨率（`virtH > virtW`）时自动切换为**上下分屏**，旋转屏幕即时重排两个分屏窗口。
  - **可拖拽悬浮分屏分割条 + 实时比例调整（`25% ~ 75%`）**：分屏接缝处出现高对比度磨砂分割手柄，按住拖拽可实时调节左右/上下窗口占比，松手自动动态调整双任务尺寸（无需重启应用）；轻点分割条或 Dock「◫ 分屏」可呼出快捷浮窗，支持 `3:7 / 4:6 / 5:5 / 6:4 / 7:3` 一键预设、`⇄/⇅ 互换位置` 与 `✕ 退出分屏`。
  - **多种分屏入口**：
    - Dock 栏新增 **`◫ 分屏`** 快捷按钮（一键将最近两个应用拼屏，或呼出控制浮窗）；
    - 扩展桌面应用图标 **长按 / 鼠标右键菜单** 新增 **`◫ 分屏：放到左屏 / 右屏`**（竖屏下自动显示为 **`放到上屏 / 下屏`**），放入单侧后继续轻点桌面任意第二个应用即可自动补全另一侧。
  - 后台 `ExtBridge` 在放入分屏时自动对任务执行 `setTaskResizeable` + `WINDOWING_MODE_FREEFORM` 转换与边界重排，解决部分应用默认 `fullscreen` 无法直接调整尺寸的问题。
- **Dock 栏自动隐藏与底边唤醒**
  - 默认开启「应用内 Dock 自动隐藏」（可在 Dock 快捷面板 **`📌 Dock自动隐藏`** 或手机控制台切换为「始终固定显示」）：
    - 在扩展桌面主页或快捷面板展开时 Dock 保持常驻可见；
    - 进入全屏/分屏应用且无操作闲置约 `2.6s` 后，Dock 自动平滑向下收起，仅在屏幕底端居中保留纤细的交互式触发指示条。
    - 将实体鼠标或触摸板光标移至外接屏底边中央、悬停或轻点底边触发条，Dock 立即平滑弹起唤醒；鼠标悬停在 Dock 栏区域内时自动延迟隐藏不误收。

---

## v3.2.3 (`versionCode: 8`) — 2026-11-17

### 🐛 关键问题修复（外接屏无法点击启动 / 输入注入静默失效）
- **绕过系统「后台弹出界面 (`MIUIOP 10021`)」对扩展屏桌面启动应用的静默拦截**
  - 在小米/HyperOS 等深度定制 ROM 上，应用处于 `VirtualDisplay` 时，从 Helper 进程自身调用 `startActivity()` 可能因缺少 `MIUIOP(10021)` 后台弹窗权限而被系统直接吞掉（无异常抛出，表现为点击图标有按下高亮动画却无任何软件打开）。
  - 现改为当 `LauncherActivity` 运行于扩展屏时，**优先通过 Shell 守护进程 (`BridgeClient.launchComponent` -> `ExtBridge.launchComponentOnVd`) 代理启动应用**，仅在守护未连接/返回失败时才回退本地 `startActivity()`；同时在 `ExtBridge` 启动、无线调试引导、`start_extbridge.sh` 与电脑 ADB 命令中自动授予 `appops set com.extbridge.helper 10021 allow`。
- **可感知化 `INJECT_EVENTS` 安全拦截状态 & 鼠标输入注入与光标层级修复**
  - 为 `ExtBridge` 统一封装 `invokeInjectInput()`，精确捕获 `SecurityException` 与输入注入返回值，并在状态接口 (`code=15`) 与鼠标注入返回 (`code=17`) 中透传 `sLastInjectStatus`（`1=正常` / `-1=INJECT_EVENTS 被拒绝`）。
  - 修正触摸板左/右键单击注入使用的未来时间戳序列为单调递增至当前时间（`now - 25` ~ `now`），防止部分设备 `InputDispatcher` 丢弃未来时间戳的 `ACTION_UP` 事件。
  - 修复 `BridgeService` 宿主模式下 `mDockRoot`（`10dp`）和快捷面板（`16dp`）海拔高于 `mCursorView`（`0dp`）导致 **鼠标准星被 Dock 栏挡在下层** 的层级 bug，统一提升鼠标指针至最高渲染层级（`elevation/translationZ = 48dp` + `bringToFront()`）。
  - 当系统未开启 MIUI/HyperOS「开发者选项 → USB 调试（安全设置）/ 允许模拟点击」导致触控板/鼠标准星只动画不生效时，控制台详情页与触控板顶部会立即弹出醒目红色提示与 Toast 指引开启。

- **首次启动全量权限初始化 & 进入开发者选项二次校验（含无障碍必需项）**
  - 将 **无障碍服务（`ExtBridge 平板辅助手势`）** 提升为与通知、悬浮窗并列的必需项；应用首次启动时自动顺序申请全部核心权限（Android 13+ 运行时 `POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW` 悬浮窗与无障碍服务），并提供 Android 13+「受限设置（灰色开关）」一键进应用详情解锁引导。
  - 若用户在首次启动时拒绝或跳过了任意权限（含无障碍/悬浮窗/通知），当其首次点击**「去开启无线调试 / 开发者选项」**时，会先拦截并要求补齐缺失权限；同时在 ADB/守护激活命令中自动授予 `ACCESS_RESTRICTED_SETTINGS` 解锁受限设置。

---

## v3.2.2 (`versionCode: 7`) — 2026-11-16

### 🎨 交互表达优化 & 项目定位明确
- **区分「桌面渲染分辨率」与「外接物理输出」，消除切换误解**
  - 控制台卡片明确标注上方预设与自定义宽高控制的是 **`VirtualDisplay` 真实渲染画布**（全机型 100% 生效：支持 `720p/900p` 降 GPU 负载稳帧、`4K` 超采样锐化、比例适配与竖屏旋转），状态栏同步区分显示 `虚拟渲染画布` 与 `外接物理输出 (系统/EDID协商)`。
  - 将 **目标刷新率预设与显示器硬件 EDID 模式列表** 统一收纳进默认折叠的 **「▸ 高级：外接物理信号与帧率尝试 (受设备固件限制)」** 面板，主界面只突出 **桌面渲染分辨率、四向旋转与缩放 DPI** 三项 100% 生效的核心设置；主应用按钮更名为 **「✓ 应用渲染分辨率与设置」**。
- **物理模式锁死诚实检测 (`sLastPhysResult = -3`)**
  - `ExtBridge` 在调用 `setUserPreferredDisplayMode` 后若检测到系统固件未实际切换物理档位（例如 Xiaomi HyperOS 4 下 `DisplayModeDirectorImpl` 限制 `displayId` 仅 0/1 且 `SurfaceFlinger` 拦截 `Attempt to set desired display modes for external display`），会返回明确状态 `-3`，控制台通过横幅与 Toast 告知用户“虚拟桌面已切换，当前系统固件锁定外接物理输出为 xx×yy@zzHz，已自动硬件缩放铺满”。
- **明确项目免 Root 定位与文档说明**
  - `README.md` 与激活弹窗补充说明：ExtBridge 专注 **免 Root / Shell 权限** 路线，不开发也不会维护用于改写系统框架或 `SurfaceFlinger` 的 Zygisk/LSPosed Root Hook 模块；已有的一键 Root 激活仅作省事注册与立即降权运行使用。

---

## v3.2.1 (`versionCode: 6`) — 2026-11-16

### 🐛 问题修复
- **修复部分 Android 13–16 设备物理外接分辨率与刷新率切换失效的问题**
  - 原 `DisplayManager.setUserPreferredDisplayMode(int, Display.Mode, boolean)` 三参数反射仅存在于 Android 17+，在 Android 15 / 16（如小米平板 8 Pro 等 ROM）上会静默 `NoSuchMethodException` 导致显示器始终停在默认 `1080P 60Hz`。
  - 现改为多签名级联探测并带 Shell 兜底：`DisplayManager`（3参/2参）→ `DisplayManagerGlobal`（2参/3参）→ `IDisplayManager`（2参/3参）→ `cmd display set-user-preferred-display-mode`。
  - 修复 `applyConfig()` 预设匹配逻辑（适配竖屏旋转宽高、刷新率无匹配挡位时不静默伪装成功，由控制台诚实提示 EDID 不支持该帧率或系统拒绝切换）；点击分辨率预设、自定义输入框或「对调宽高」时自动重置旧 EDID 物理模式 ID 锁定。
  - 修复 `tick()` 与异步切换时 `reassert()` 未重算缩放矩阵的问题：当 `sPhysW × sPhysH` 发生变化时自动重新执行 `applySurfaceTransform()` 与状态同步。
- **修复 App 划掉 / 强行停止后自动重启，并新增「停止服务并退出」功能**
  - 移除 `ExtBridge` 中 `sHelperProcToken.linkToDeath` 的 1.5 秒强行拉起死循环，`tick()` 仅在首次接入外接物理屏时同步唤醒 Helper；`notifyHelperSync()` 在 Helper 未存活时不再发 `STATE_SYNC` 广播触发唤醒；`BridgeService` 改为 `START_NOT_STICKY`。
  - 在控制台顶部操作区与待机通知栏新增 **「⏻ 停止服务并退出」**（调用 `BridgeClient.shutdownDaemon()` 触发 `code == 99` + `SHUTDOWN` 广播，先恢复外接屏并退出守护进程，再销毁前台通知与 App 任务），不再使用外接屏时可一键彻底关闭。

---

## v3.2.0 (`versionCode: 5`) — 2026-11-16

### ✨ 新增功能
- **四向输出画面旋转与竖屏桌面 (`0° / 90° / 180° / 270°`)**
  - 在 `ExtBridge` 图层合成管线（`applySurfaceTransform`）引入 `Matrix` + `RectF` 硬件旋转与居中矩阵变换，支持将物理横屏输出的 Type-C 显示器/便携屏竖放后点对点满屏竖屏显示，或倒置 `180°` 显示。
  - 切换 `90° / 270°` 时通过 `liveResizeOrRebuild()` 对 `VirtualDisplay` 与 `BLASTBufferQueue` 执行无感热重建与宽高对调，**不中断已打开的后台应用**；旋转角度持久化保存于 `/data/local/tmp/extbridge.conf`。
- **控制台与扩展屏快捷面板同步接入**
  - `MainActivity` 新增 **「输出桌面旋转方向」**（`0° 正常横屏` / `90° 顺时针竖屏` / `180° 倒置横屏` / `270° 逆时针竖屏`）与 **`⇅ 对调宽高`**（支持在横屏显示器上保持 `0°` 居中投射竖屏桌面）。
  - 扩展屏右下角快捷面板（`BridgeService`）新增 **`↻ 旋转`** 按钮，可直接在扩展屏侧循环切换输出方向。
- **竖屏排版与边界适配**
  - 放宽分辨率校验 `isValidDims()`，完整支持 `1440×2560`、`1080×1920`、`2160×3840` 等竖屏尺寸；`computeOverlayDpi()` 改为按短边限幅以优化竖屏 Dock 缩放；`calcFreeformRect()` 适配竖屏自由小窗宽高比例。

---

## v3.1.0 (`versionCode: 4`) — 2026-11-16

### ✨ 新增与改进
- **新增 Root 一键激活模式（免 ADB / 免开发者选项）**
  - 在激活弹窗新增 **「👑 Root 激活 (免 ADB 权限)」**，兼容 Magisk / KernelSU / APatch；支持勾选 **「启动软件时自动 Root 激活」**，重启设备打开 App 即可静默恢复后台守护进程。
  - `ExtBridge` 守护进程以 Root 拉起后自动通过 `Os.setgid(2000)` / `Os.setuid(2000)` 降权至 `shell` 身份运行，降低高权限驻留风险。
- **首次插屏跨分辨率自动适配**
  - 在用户尚未保存自定义配置（`/data/local/tmp/extbridge.conf` 不存在）时，自动按接入外接显示器的原生 `physW × physH @ physFps` 创建虚拟桌面，并按短边比例自动计算桌面 DPI（限幅 `160 ~ 320`）。
- **主屏手势隔离加固 & 工程化整理**
  - 在 `applyDisplayFullscreenPolicy()` 中通过反射补充 `IWindowManager.setShouldShowSystemDecors(vdId, false)`，加强对不同系统 ROM 主屏全面屏手势的兼容隔离。
  - `MainActivity` 标题改为动态读取安装包 `versionName`；补充 GitHub Actions 自动编译工作流 `.github/workflows/build.yml`。

---

## v3.0.0 (`versionCode: 3`)

### 🎉 核心基线功能
- 基于 `VirtualDisplay` + `SurfaceControl` 的真·双屏独立扩展桌面，支持一键切换原生镜像模式。
- 手机端内建 `arm64` `adb` 与 `extbridge.jar`，支持本机无线调试 mDNS 自动配对自激活（自动屏蔽 VPN 虚拟网卡干扰）。
- 独立桌面启动器（同步系统壁纸 + 高斯毛玻璃、中文/拼音首字母搜索）、底部 Dock 任务栏与 Win11 风格快捷控制中心。
- 手机端全高大触摸板与软键盘文字转发、硬件鼠标映射热切。
- 按应用粒度管理的四种显示兼容模式（跟随系统默认 / 强制横屏全屏 / 竖屏居中兼容 / 自由小窗模式），以及可选无障碍手势兜底服务。
