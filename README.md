# 触灵映射（TouchLingMap）v0.4.0

小米带背屏机型（**小米 17 Pro / Pro Max** 等，基于 HyperOS）的 **背屏 → 主屏 映射 + 回触** 工具。
背屏显示主屏镜像，**在背屏上直接点按 / 滑动就能操作主屏**（灵触），或使用精密触控板 / 体感空鼠。无 Root 也能用（Shizuku）。

> 📱 开发测试机：小米 17 Pro Max（Android 16 / HyperOS 3，未 Root，Shizuku 通道）
> 📦 最新安装包见 [Releases](../../releases) 或 Actions 构建产物

## ✨ 功能

| 功能 | 说明 |
|---|---|
| **灵触映射** | 背屏 = 压缩版主屏（MediaProjection 镜像），背屏上直接点按/滑动，坐标线性映射注入主屏 |
| **精密触控板** | 背屏当触控板，**光标显示在主屏上**（悬浮窗），滑动移动、轻点点击、原地停 0.4 秒后拖动 = 拖拽；灵敏度 0.5x~3x 可调 |
| **体感空鼠** 🆕 | 倾斜手机（陀螺仪）移动**主屏光标**，轻点屏幕 = 在光标处点击 |
| **双指滚动** 🆕 | 双指上下滑 = 页面滚动（刷视频/文章神器） |
| **快捷面板** 🆕 | 双指轻点 = 弹出 返回 / 主页 / 任务 / 截图 / 音量± |
| **黑遮罩** | 背屏常亮时盖半透明黑层防烧屏（0~80% 可调） |
| **光标主题** 🆕 | 光标样式（圆点/十字/箭头/方框）× 颜色（蓝/白/红/绿/黄）× 大小（小/中/大） |
| **背屏样式** 🆕 | 背屏显示：镜像 / 纯黑 / 网格（当触控板时更直观，且省电） |
| **方向反转** 🆕 | 左右反转 / 上下反转（触控板与体感通用，适配不同握持方向） |
| **背屏玩具** 🆕 | 幸运转盘 / 真心话大冒险 / 电子木鱼（敲盅+功德，带音效与涟漪）/ 骰子 —— 纯背屏本地互动，一键「切换玩具」 |
| **双注入通道** | **Root**（常驻 `su`，延迟最低）/ **Shizuku**（无 Root）/ **自动** |

## 🏗 工作原理（v0.4.0 架构）

```
MainActivity（点“开始映射” → 投屏授权）
    │
    ▼
MirrorService（前台服务，持有 MediaProjection）
    │ ① 主屏隐形启动 RearActivity（全屏+全透明，WM 可见但不打扰）
    │ ② 注入器 exec：
    │      · 点亮背屏（MIUI 私有电源事务 CAMERA_CALL）
    │      · am display move-stack <taskId> <背屏id>  ← 把任务搬去背屏
    │      · 失败自动重试 ×3（检测 RearActivity.arrived）
    │ ③ keeper：周期 `am force-stop com.xiaomi.subscreencenter`（防抢屏）
    ▼
RearActivity（运行在背屏）
    │ 镜像：SurfaceView ← VirtualDisplay ← MediaProjection
    │ 触摸：TouchMapper（灵触/触控板/体感/双指状态机）
    ▼
Injector（统一接口）
    ├─ RootInjector：常驻 su root shell（/system/bin/input 绝对路径）
    └─ ShizukuInjector：常驻 sh（管道发触摸）+ exec（独立进程跑关键命令）
```

### 关键经验（HyperOS 3 / 小米17 实测）

1. **不能直接 `startActivity`/`Presentation` 到背屏** — 会 `SecurityException: launchDisplayId` 或 `Failed transaction`（背屏是独立显示组，仅属主内容）。
   正确做法：**主屏正常启动 Activity → `am display move-stack <taskId> 1` 把任务整体搬过去**。
2. **背屏休眠（state=DOZE_SUSPEND）时搬运可能失败** — 先点亮背屏（`service call power 16777210 ... CAMERA_CALL`）再搬。
3. **`com.xiaomi.subscreencenter` 会抢背屏** — 需要持续 `force-stop`（keeper 线程）。
4. **注入器的持久管道会静默失效** — 关键命令改用 `exec()`（独立进程 + 回读输出），日志真实可查。
5. **Actions 每次构建签名不同** → 仓库内置固定 keystore（`signing/touchling.keystore`），保证覆盖安装。

## 🚀 使用

1. 安装 APK（Android 13+，需允许安装未知来源）
2. 无 Root：安装 [Shizuku](https://shizuku.rikka.app/) → 无线调试启动 → 点 App 内「授权 Shizuku」
3. 两条用法（**互不干扰**）：
   - **只显示内容**：底部「🖼 背屏内容」选好主题/玩具 → 点「🖼 只把主题/玩具显示到背屏」——**不需要投屏授权、不开启触摸映射**
   - **触摸映射**：底部「🎛 控制」点「▶ 开始映射」→ 同意投屏授权 → 背屏镜像 + 背屏触摸操作主屏

### 界面（v1.0.0：底部导航分页 + 透明玻璃标签栏）

| 页面 | 内容 |
|---|---|
| 🎛 控制 | 状态卡 / 开始映射 / 只显示主题到背屏 / 停止 / 授权 Shizuku / 使用提示 |
| 🖼 背屏内容 | HTML 主题（木鱼·时钟·转盘·我的 AI）/ 内置原生玩具（转盘·真心话·木鱼·骰子）/ 背屏底色（镜像·纯黑·网格）/ 一键只上屏 |
| ✨ AI 工坊 | 描述 + API Key → AI 生成 HTML 背屏主题（自动存为「我的 AI」主题） |
| ⚙️ 设置 | 注入通道 / 映射模式 / 灵敏度 / 黑遮罩 / 光标样式·颜色·大小 / 方向反转 / 体感与双指 |

> 排障：App 全程写日志（`logcat -s TouchLing`，或 `run-as com.touchling.mapper cat files/diag.log`），
> 关键链路（授权/搬运/点亮/触摸）都有记录，出问题一眼定位。

## 📜 版本历史

| 版本 | 要点 |
|---|---|
| v2.2.0 | **evdev 直读背屏触摸**（内核层 `getevent` 流式读取，免投屏+触控板模式下背屏视图退为防误触）；**控制中心磁贴**一键启停；**开机自启**；**按应用自动启停**（白名单 + 使用情况访问）；**悬浮开关**（可拖动小圆点）；新增 Release 分发 |
| v2.1.0 | **新增「体感光标」控制模式**（与灵触映射/精密触控板并列，可配免投屏使用）；**光标默认改为箭头**；模式摘要与悬浮窗权限检查同步体感；免投屏模式兼容体感 |
| v2.0.0 | **免投屏触控模式**（背屏黑屏当触控板，**不申请屏幕捕获权限**，原理逆向自「妙妙背屏」）；新增「🎮 手感」页：触控板速度 / 平滑滤波 / 陀螺仪死区 / 光标大小(dp) / X·Y反转 / 光标回中 / 陀螺仪校准 / 背屏方向(0~270°)；背屏设备手动指定；光标尺寸改为 dp 连续可调 |
| v1.3.0 | **背屏看门狗（自愈）**：背屏息屏后任务被系统退回主屏 → 自动重新点亮 + 重新搬运；HTML 主题右上角「退出」按钮；主题库「预览」（主屏弹窗）；`arrived` 状态实时同步 |
| v1.2.0 | **修复「只显示内容到背屏」失效**（Android 14 前台服务类型改为 specialUse，原来是 mediaProjection 导致服务启动即崩）；HTML 主题尺寸自适应（宽视口 + 溢出自动缩放）；AI 提示词强制相对单位；上屏前检查 shell 权限并提示 |
| v1.0.0 | 「只显示主题/玩具到背屏」独立通道（免投屏授权、不开触摸映射）；页面重排（控制/背屏内容/AI工坊/设置）；透明玻璃导航；内置 3 套 HTML 主题（木鱼·时钟·转盘） |
| v0.9.0 | 主界面改底部导航分页 + 液态玻璃标签栏 |
| v0.8.0 | HTML 主题引擎（WebView + TouchLing JS 桥）+ AI 生成主题 |
| v0.7.0 | 背屏互动玩具（转盘/真心话/电子木鱼/骰子） |
| v0.6.0 | 版本号动态显示；方向反转开关；光标样式·颜色·大小；背屏底色（镜像/纯黑/网格） |
| v0.5.0 | 光标改为**主屏悬浮窗**显示（背屏当触控板） |
| v0.4.0 | 体感空鼠 + 双指滚动 + 快捷面板；`exec` 可靠执行通道；点亮背屏 + 搬运重试 |
| v0.3.4 | 引入固定签名 keystore；`am display move-stack` 搬运方案 |
| v0.3.2 | 改用「主屏启动 + 任务搬运」思路（替代 Presentation） |
| v0.3.1 | RearActivity 架构 + 全程诊断日志 |
| v0.3.0 | 架构重写：诊断日志 / 背屏 Activity / 处刑 + 点火 |
| v0.1.x | 初版：灵触映射 + 触控板 + 双注入通道 |

## 📚 参考

- [tpkarras/Mirror2RearUltra](https://github.com/tpkarras/Mirror2RearUltra) — 背屏镜像 + 电源事务点亮
- [GoldenglowSusie/MiRearScreenSwitcher](https://github.com/GoldenglowSusie/MiRearScreenSwitcher) — 小米17 任务搬运 / 处刑背屏中心
- [wmqc97/MAML-Theme-Reference](https://github.com/wmqc97/MAML-Theme-Reference) — 背屏系统内部实证文档

## ⚠️ 说明

- 仅供个人学习与自用设备折腾，使用风险自负
- 映射时官方背屏中心被强制停止，停止映射后会自动恢复（`monkey` 拉起）
