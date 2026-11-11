# Bilibili Accelerator for Android

**一个致力于改善哔哩哔哩客户端视频加载体验的 LSPosed 增强模块。**

本项目是 [realzza/bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 的非官方 Android 移植版。

模块在受支持的哔哩哔哩客户端中拦截点播地址，可将识别出的 PCDN 线路切换至官方 UPOS 镜像，或为符合条件的媒体启用 BTR 分片拉取。实际效果取决于客户端版本、CDN 签名和网络环境。

## 核心特性

- **点播 PCDN 过滤**：识别已知 PCDN 域名和带媒体特征的非标准端口，尝试恢复调度地址里的 `xy_usource` 或切换至配置的官方 UPOS 节点。
- **MCDN 调度**：`proxy-all` 将识别到的 MCDN 流交给中继，`proxy-v1` 仅中继 `/v1/resource/`，普通 `/upgcxcode/` 媒体尝试切换至 UPOS，其他路径按兼容规则处理。`replace` 尝试将可直接替换的 MCDN 地址切换至 UPOS，`/v1/resource/` 仍走中继。
- **BTR 点播分片**：通过回环代理并行拉取 Range，并按顺序交付。自动并发在开播或 Seek 后的前几片使用较大的预取窗口，稳定后回落。调度优先使用当前媒体已测得较快的线路，并低频尝试未测量或测量已过期的节点。视频手动并发可选 2–16，实际窗口受节点类型、内存预算和 Host 并发限制约束。
- **分片大小**：视频自动选择 256 KiB、512 KiB 或 1 MiB，手动选择范围为 128 KiB–2 MiB。DASH 音频使用 128 KiB 分片、最多 2 个在途分片，以及独立的连接池、工作线程和预取预算。
- **CDN 候选与回退**：可以配置最多 32 个官方 CDN，单个会话的节点池上限包含主节点。优先使用宿主提供的同轨备用，合成候选先校验总长度和样本字节再拉取分片。分片中断后，备用源提供匹配的强 ETag 时可只续传剩余字节。中继不可用时，只有符合直通条件的原始媒体地址才能作为回退目标。
- **剧集与签名保护**：支持剧集 BTR 开关。识别出的试看与 DRM 流绕过 BTR，默认保留 Akamai 签名地址。
- **直播直通**：识别出的 FLV、HLS 及其他直播地址和备用列表交给宿主播放器处理。
- **统计与诊断**：看板显示改写和 BTR 指标。开启 Debug 日志后，可导出含模块与宿主进程诊断事件的报告。

---

## 安装与激活

### 前置要求

* 已 Root 的 Android 设备
* 已安装并激活支持新版标准的框架（如最新版的 [LSPosed](https://github.com/LSPosed/LSPosed) 或 Vector 等）
* 哔哩哔哩官方客户端（支持标准版 / 概念版 / 国际版 / HD 版等）

### 激活步骤

1. 从 Releases 下载对应版本的 APK 并安装。
2. 打开你的框架管理器（如 **LSPosed Manager**），在“模块”列表中找到 **Bili Accelerator** 并勾选启用。
3. 在模块的作用域设置中，勾选你需要加速的 B 站客户端（如：`tv.danmaku.bili` / `com.bilibili.app.in` / `com.bstar.intl` 等）。
4. **强行停止**（杀掉后台）哔哩哔哩客户端。
5. 重新打开哔哩哔哩。
6. 打开本模块的 APP，若顶部状态显示 **“✅ 已激活”**，即代表配置成功。此时去 B 站随意播放一个视频，即可在面板中观察到拦截与重定向的计数正在增加。

---

## 安全说明

- BTR 本地代理只监听 `127.0.0.1`，不会监听局域网或公网地址。
- BTR 上游和重定向目标必须通过 HTTPS 与媒体域名校验。BTR 使用的 DNS 解析结果含私网、回环或其他保留地址时会被拒绝。
- 配置 Provider 不可用且本地没有上一次已确认配置时，模块采用 fail-closed，不会用硬编码镜像静默改写播放请求。
- 为兼容播放器访问本地 HTTP，目标进程允许回环地址通过带 Host 的 cleartext 判定。无参判定仅在 BTR 代理运行期间临时放宽。
- 本模块声明的 Android 权限很少；不要从非可信渠道安装 APK。

## 遇到问题？

**Q1: 打开模块 APP 显示“未激活”怎么办？**

* 请检查框架管理器中是否已勾选启用本模块，并且**正确勾选了哔哩哔哩客户端作为作用域**。
* 每次更新模块或修改作用域后，必须**强行停止**（杀掉后台）哔哩哔哩客户端，然后重新打开才会生效。
* 如果确认操作无误但依然显示未激活，请尝试重启手机或重启框架服务。

**Q2: 播放视频时一直转圈缓冲，或者提示加载失败？**

* 可能是 B 站服务器临时调整了策略，或者你的网络运营商对我们重定向后的高速节点连接不佳。
* 部分极旧或最新的 B 站客户端版本可能会更改底层的播放机制。建议你在框架中暂时关闭本模块，排查是否为网络本身的问题。如果确认是模块导致，请在 Issue 中反馈你使用的 B 站版本号。

**Q3: 播放了视频，但面板里的拦截/重定向统计数据一直为 0？**

* 请先确认模块状态是否为“已激活”。
* 在非高峰时段或播放冷门视频时，B 站可能本身就为你分配了官方的优质 CDN，没有触发模块的拦截规则，此时计数不增加属于正常现象。
* 如果持续多天、播放任何视频都不增加，可能是 B 站客户端的更新导致模块的拦截机制失效，欢迎你携带日志向我提交 Issues，并请关注模块的后续更新。

---

## 反馈与问题提交

如果你在使用本模块时遇到了 Bug、闪退、或者有任何功能建议，请直接在**本仓库的 Issues** 页面提交，我会尽快处理。

---

## 编译指南

如果你想自行编译此项目：

1. 使用 **Android Studio** 打开 `bilibili-accelerator-android` 项目。
2. 等待 Gradle 同步完成后，点击菜单栏：**Build > Build Bundle(s) / APK(s) > Build APK(s)**。
3. 或者在终端运行以下命令：
```bash
# 构建正式 Release 版本
./gradlew assembleRelease

# 或构建开发 Debug 版本
./gradlew assembleDebug

```

4. Debug APK 位于 `app/build/outputs/apk/debug/`。Release 构建需要 `app/release.jks` 或 `app/signing/release.jks`，以及环境变量 `KEYSTORE_PASSWORD` 和 `KEY_ALIAS`。Release APK 位于 `app/build/outputs/apk/release/`。

---

## 鸣谢与开源协议

本项目基于 [MIT License](LICENSE) 许可协议开源。

* 特别感谢上游项目 [realzza/bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 的原作者。本 Android 模块的核心逻辑架构与拦截规则均参考自该项目！
* 特别感谢 [MrTangLuyao/Bilibili-thread-ripper (BTR)](https://github.com/MrTangLuyao/Bilibili-thread-ripper) 项目。本模块在业务层通过 URL Hook + 本地回环代理进行多线程 Range 分片并发拉取（BTR 调度加速）的核心思路参考自该项目！
