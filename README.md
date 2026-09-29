# 触灵映射（TouchLingMap）

小米带背屏机型（**小米 17 Pro / Pro Max** 等，基于 HyperOS）的 **背屏 → 主屏 映射 + 回触** 工具。
背屏显示主屏镜像，**在背屏上直接点按 / 滑动就能操作主屏**（灵触），或使用精密触控板 / 体感空鼠。无 Root 也能用（Shizuku）。

> 📱 开发测试机：小米 17 Pro Max（Android 16 / HyperOS 3，未 Root，Shizuku 通道）
> 📦 最新安装包见 [Releases](../../releases) 或 Actions 构建产物

## ✨ 功能

| 功能 | 说明 |
|---|---|
| **灵触映射** | 背屏 = 压缩版主屏（MediaProjection 镜像），背屏上直接点按/滑动，坐标线性映射注入主屏 |
| **精密触控板** | 背屏当触控板，**光标显示在主屏上**（悬浮窗），滑动移动、轻点点击、原地停 0.4 秒后拖动 = 拖拽；灵敏度 0.5x~3x 可调 |
| **体感空鼠** | 倾斜手机（陀螺仪）移动**主屏光标**，轻点屏幕 = 在光标处点击 |
| **双指滚动** | 双指上下滑 = 页面滚动（刷视频/文章神器） |
| **快捷面板** | 双指轻点 = 弹出 返回 / 主页 / 任务 / 截图 / 音量± |
| **黑遮罩** | 背屏常亮时盖半透明黑层防烧屏（0~80% 可调） |
| **光标主题** | 光标样式（圆点/十字/箭头/方框）× 颜色（蓝/白/红/绿/黄）× 大小（dp 可调） |
| **背屏样式** | 背屏显示：镜像 / 纯黑 / 网格（当触控板时更直观，且省电） |
| **方向反转** | 左右反转 / 上下反转（触控板与体感通用，适配不同握持方向） |
| **背屏玩具** | 幸运转盘 / 真心话大冒险 / 电子木鱼（敲盅+功德，带音效与涟漪）/ 骰子 —— 纯背屏本地互动，一键「切换玩具」 |
| **双注入通道** | **Root**（常驻 `su`，延迟最低）/ **Shizuku**（无 Root）/ **自动** |
| **免投屏触控** | 背屏黑屏当触控板，**不申请屏幕捕获权限**；可选 `evdev 直读`（内核层 `getevent` 读背屏触摸，自动识别触摸设备） |
| **三种控制模式** | 灵触映射 / 体感光标 / 精密触控板，可配合手感参数（速度·平滑·死区·光标大小·反转·回中·校准·背屏方向） |
| **HTML 主题** | 内置木鱼 / 时钟 / 转盘，可 **AI 生成**（OpenAI 兼容接口）并管理主题库，触摸交互可调用原生能力 |
| **自动化** | 控制中心磁贴一键启停 / 开机自启 / 按应用自动启停（白名单）/ 悬浮开关（可拖动小圆点） |
| **背屏看门狗** | 背屏息屏被系统退回主屏时，自动重新点亮 + 重新搬运（自愈） |

## 🏗 工作原理

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

### 界面：底部导航分页 + 透明玻璃标签栏

| 页面 | 内容 |
|---|---|
| 🎛 控制 | 状态卡 / 开始映射 / 只显示主题到背屏 / 停止 / 授权 Shizuku / 使用提示 |
| 🖼 背屏内容 | HTML 主题（木鱼·时钟·转盘·我的 AI）/ 内置原生玩具（转盘·真心话·木鱼·骰子）/ 背屏底色（镜像·纯黑·网格）/ 一键只上屏 |
| ✨ AI 工坊 | 描述 + API Key → AI 生成 HTML 背屏主题（自动存为「我的 AI」主题） |
| ⚙️ 设置 | 注入通道 / 映射模式 / 灵敏度 / 黑遮罩 / 光标样式·颜色·大小 / 方向反转 / 体感与双指 |

> 排障：App 全程写日志（`logcat -s TouchLing`，或 `run-as com.touchling.mapper cat files/diag.log`），
> 关键链路（授权/搬运/点亮/触摸）都有记录，出问题一眼定位。

> 📜 完整更新记录见 [CHANGELOG.md](CHANGELOG.md)；安装包请到 [Releases](https://github.com/zmxiaoyao/TouchLingMap/releases) 下载。

## 📚 参考

- [tpkarras/Mirror2RearUltra](https://github.com/tpkarras/Mirror2RearUltra) — 背屏镜像 + 电源事务点亮
- [GoldenglowSusie/MiRearScreenSwitcher](https://github.com/GoldenglowSusie/MiRearScreenSwitcher) — 小米17 任务搬运 / 处刑背屏中心
- [wmqc97/MAML-Theme-Reference](https://github.com/wmqc97/MAML-Theme-Reference) — 背屏系统内部实证文档

## ⚠️ 说明

- 仅供个人学习与自用设备折腾，使用风险自负
- 映射时官方背屏中心被强制停止，停止映射后会自动恢复（`monkey` 拉起）
