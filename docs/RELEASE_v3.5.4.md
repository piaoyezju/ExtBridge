# ExtBridge `v3.5.4` (`versionCode: 26`) 更新文档

## 1. 版本更新概览

`v3.5.4` 聚焦修复小米手机（MIUI 13/14、HyperOS 1.0/2.0，Android 11~15）在**免 Root 本机无线调试**激活时高频出现的 **`远程 Shell 启动失败: ERR_STAGE_JAR`** 问题，并完整合并了 **Root 一键激活「守护进程命令已下发，但暂未收到 Binder 握手」** 的全场景修复与 GitHub Actions 自动正式签名流水线升级。

---

## 2. 无线调试 `ERR_STAGE_JAR` 根因与三级投递重构

### 问题复盘（为什么在小米手机上集中触发，而 Android 16 平板未触发？）
1. **系统 Toybox `unzip -tq` 参数不兼容与 `d8` 流式 ZIP 头问题（主因）**
   - `v3.5.3` 在安全加固中新增了 `unzip -tq "$TMP_JAR"` 压缩包自检。Android 16（HyperOS 3）的新版 Toybox 为 `unzip` 增加了 `-t` 参数，因此平板测试通过；但在 **Android 11 ~ Android 14 及早期 Android 15 的小米手机**上，系统内置 Toybox `unzip` 仅支持 `-d/-l/-n/-o/-p/-q/-v`，**不存在 `-t` 选项**，执行即报 `unzip: Unknown option 't'`（退出码 `1`，被重定向隐藏后统一回显 `ERR_STAGE_JAR`）。
   - 同时，`d8` 默认产出的 `extbridge.jar` 带有流式数据描述符（`flag_bits=0x0808`，本地文件头中 `compressed_size=0`），部分旧版 Toybox 顺序解析 ZIP 时亦会报错。
2. **小米手机未开「USB 调试（安全设置）」/ 手机分身 / Split APK 导致 `pm path` 提取受限（次因）**
   - 小米手机开启「USB 调试（安全设置）」强制要求插 SIM 卡与登录小米账号，大量仅开启「无线调试」的手机、手机分身（`User 10`）或应用商店分包安装场景下，远程 Shell 直接依赖 `pm path com.extbridge.helper | head -n1` 与读取 `/data/app/...` 存在路径为空或权限受限风险。

### 修复方案
- **无线调试三级免 `unzip` 直推管线 (`AdbBootstrap.java`)**：
  1. **Tier 1（首选）**：由客户端进程直接从自身内存 `AssetManager` 读取 `assets/extbridge.jar` 计算 `SHA-256`，并通过内置 `libadb.so` 执行原生 **`adb push`** 直传至 `/data/local/tmp/extb_<16位随机串>.jar`——**全程零 `pm path`、零 `/data/app` 访问、零 `unzip` 调用**，即使未插卡、未开启「USB 调试（安全设置）」或处于手机分身环境也能 100% 释放成功。
  2. **Tier 2（自动回退）**：若目标 ROM 限制了 ADB SYNC 传输，自动切换为通过 `adb shell` 标准输入流（`cat >` + `set -C` 原子防符号链接）直接写入字节流。
  3. **Tier 3（兜底）**：遍历当前用户 `--user <uid>` 与主用户的全部 `pm path` 分包路径提取。
- **全链路移除 `unzip -tq` 并标准化 JAR 结构**：
  - 移除无线调试、Root 激活、电脑 ADB 一键命令及 `start_extbridge.sh` 中全部 `unzip -tq` 调用，改用 **`PK` 文件魔数 + 256 位 `SHA-256` 强校验（及 `app_process --verify-helper` 虚拟机加载校验）**。
  - 编译脚本 `build_v3.ps1` 自动归一化 `extbridge.jar` ZIP 文件头（清除 `0x0008` 流式标志位），并在 `build.gradle.kts` 中配置 `noCompress += "jar"` 避免 APK 二次压缩。
- **进程匹配与电脑端命令容错加固**：
  - `pgrep`/`pkill` 改为精确匹配进程首参 `^app_process.*ExtBridge` 与 `^/system/bin/app_process.*ExtBridge`，避免贪婪正则误伤外层启动 Shell。
  - 电脑端 `adb shell` 一键激活命令对 `appops` 授权增加容错包裹，防止未开启「USB 调试（安全设置）」的小米手机在拉起守护进程前中断。

---

## 3. Root 一键激活「未收到 Binder 握手」全场景修复（已合并）

- **独立高优握手通道 (`sBinderPushExec`)**：守护进程启动 `<250ms` 内立即向客户端推送 `IBridgeControl`，并在 `+400ms / +1100ms / +2400ms` 自动补推重试，所有慢速 `dumpsys` / `appops` 自愈任务全部异步后置。
- **Magisk / KernelSU / APatch 全兼容**：消除跨 `su 2000` 环境变量丢失问题；适配 Magisk「独立挂载命名空间（Isolated Mount Namespace）」不可见应用私有目录时的自动回退与哈希强校验；先降权 `uid=2000` 再经 `setsid runcon u:r:shell:s0` 脱离会话切入标准 `u:r:shell:s0` 域，确保 `untrusted_app` 双向 Binder 通信不被 SELinux 拦截。
- **双通道 Binder 投递**：在 `BridgeProvider` 之外新增受签名级权限 `android.permission.INJECT_EVENTS` + UID 校验保护的 `com.extbridge.helper.BINDER_PUSH` 显式广播兜底，兼容拦截后台 ContentProvider 唤醒的定制 ROM。
