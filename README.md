# 触灵映射（TouchLingMap）v0.1

小米带背屏机型（小米17 Pro Max / 小米18 系列）的 **背屏 → 主屏映射** 工具，无 Root，基于 Shizuku。

## 功能

| 功能 | 说明 |
|---|---|
| 灵触映射 | 背屏 = 压缩版主屏（MediaProjection 镜像），背屏上直接点按/滑动，坐标线性映射注入主屏 |
| 精密触控板 | 滑动移动光标（灵敏度 0.5x~3x 可调）；轻点 = 点击；原地停 0.4 秒后拖动 = 拖拽 |
| 黑遮罩 | 背屏常亮时盖半透明黑层防烧屏（0~80% 可调） |
| **双注入通道** | **Root**（常驻 `su`，延迟最低，首次弹 Root 授权）/ **Shizuku**（无 Root，无线调试启动）/ **自动**（有 Root 优先 Root） |

## 工作原理

```
背屏 Presentation（接收触摸 + SurfaceView 显示镜像）
    │  触摸事件
    ▼
TouchMapper（坐标压缩 / 光标手势状态机）
    │  DOWN / MOVE / UP
    ▼
Injector（统一接口）
 ├─ RootInjector：常驻 su root shell（/system/bin/input 绝对路径）
 └─ ShizukuInjector：常驻 sh（shell uid 拥有 INJECT_EVENTS，无 Root）
    ▼
主屏（全局触摸注入）
```

- **镜像**：`MediaProjection` + `VirtualDisplay` 输出到背屏 Presentation 的 SurfaceView
- **注入**：`input motionevent` 逐行写入常驻 shell 的 stdin（带 stdout/stderr 排水线程，防管道阻塞）
- **背屏窗口**：`Presentation` 显示在非默认 Display 上，`FLAG_KEEP_SCREEN_ON` 保持背屏常亮
- **通道选择**：设置页「注入通道」自动 / Shizuku / Root 三选一，切换后需停止再开始

## 构建方法

### 方法 A：GitHub Actions（推荐，手机即可操作）
1. 把本仓库推到你的 GitHub
2. 打开仓库 → Actions → `build-apk` → Run workflow
3. 构建完在 Artifacts 里下载 APK

### 方法 B：Android Studio
1. Android Studio 打开本工程（JDK 17）
2. 若提示缺 gradle-wrapper.jar：终端执行 `gradle wrapper --gradle-version 8.9`
3. Run → 生成 debug APK

## 使用步骤（小米17 Pro Max）

1. **安装 Shizuku**（Google Play / GitHub releases）
2. 启动 Shizuku：设置 → 我的设备 → 全部参数 → 连点 MIUI 版本开开发者选项 → 开发者选项 → **无线调试** → 配对（用 Shizuku 的配对码）→ Shizuku 内"启动"
3. 打开本 App → 点 **"授权 Shizuku"** → 同意
4. 选模式/灵敏度/遮罩 → 点 **"开始映射"** → 同意系统的投屏授权
5. 翻到背屏即可操作；点"停止映射"退出

> 每次重启手机后 Shizuku 需重新启动（无线调试无需电脑，配对一次即可）。

## 已知限制 / 风险点（v0.1 待实测）

1. **与系统背屏中心的焦点竞争**：`com.xiaomi.subscreencenter` 可能抢占背屏窗口，若触摸无反应需先关掉系统背屏界面（类似 MRSS 的做法）
2. **注入延迟**：逐条 shell 命令注入，滑动跟手度取决于设备性能；后续版本改用 `IInputManager.injectInputEvent` 直连 Binder（需 hidden API）
3. **镜像宽高比**：目前是"压缩铺满"（与原版触灵一致）；若需等比缩放+留黑边可加选项
4. **背屏分辨率/触摸坐标** 需在真机上校准

## 路线图

- [x] MVP1：背屏镜像 + 灵触映射 + Shizuku 注入
- [x] MVP2：触控板模式 + 灵敏度 + 光标
- [ ] MVP3：体感光标（陀螺仪）
- [ ] MVP4：悬浮球开关 + 按 App 自动开启 + 控制中心磁贴
- [ ] MVP5：注入优化（Binder 直连）、等比缩放选项

## 权限说明

- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：维持镜像
- `POST_NOTIFICATIONS`：前台服务通知
- Shizuku 授权：触控注入（不联网、不读写文件）

## License
MIT