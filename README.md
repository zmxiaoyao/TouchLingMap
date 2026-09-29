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
3. 点「开始映射」→ 同意投屏授权
4. 背屏自动点亮并出现主屏镜像 → 直接触摸操作

> 排障：App 全程写日志（`logcat -s TouchLing`，或 `run-as com.touchling.mapper cat files/diag.log`），
> 关键链路（授权/搬运/点亮/触摸）都有记录，出问题一眼定位。

## 📜 版本历史

| 版本 | 要点 |
|---|---|
| v0.4.0 | 体感空鼠 + 双指滚动 + 快捷面板；`exec` 可靠执行通道；点亮背屏 + 搬运重试；主屏隐形窗口 |
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
