# KioskAndroid 开发文档

> Android Kiosk 自助终端锁屏应用 | 版本 1.0.7

---

## 1. 项目概述

将 Android 设备锁定为专用自助终端（信息亭、排队机、数字标牌），用户无法退出应用或访问系统桌面。

### 核心技术栈

| 层级 | 技术 |
|------|------|
| 设备锁定 | Device Owner（设备所有者） + Lock Task Mode |
| UI 全屏 | FLAG_FULLSCREEN + WindowInsetsController |
| 导航栏控制 | DPI 动态切换（240 ↔ 200）|
| WebView | Android WebView（预留，用于加载 Web 应用）|
| 构建 | Gradle 8.2 + AGP 8.2.0 + JDK 17 |
| CI/CD | GitHub Actions（Push 自动构建 + 签名）|

### 应用标识

```
applicationId: com.kiosk.app
包名:          com.kiosk.app
```

---

## 2. 项目结构

```
KioskAndroid/
├── app/
│   ├── build.gradle.kts              # 应用模块构建配置
│   ├── proguard-rules.pro            # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml        # 清单文件（权限、组件声明）
│       ├── java/com/kiosk/app/
│       │   ├── MainActivity.java      # 主 Activity（核心逻辑）
│       │   ├── KioskDeviceAdminReceiver.java  # 设备管理器接收器
│       │   └── BootReceiver.java      # 开机自启接收器
│       └── res/
│           ├── layout/activity_main.xml   # 主界面布局
│           ├── values/strings.xml         # 字符串资源
│           ├── values/themes.xml          # 主题样式
│           ├── xml/device_admin.xml       # 设备管理员策略
│           └── drawable/                  # 图标资源
├── build.gradle.kts                  # 根构建文件
├── gradle.properties                 # Gradle 属性配置
├── .github/workflows/android-build.yml  # CI/CD 构建流水线
└── update_kiosk.bat                  # 设备部署工具（外部）
```

---

## 3. 核心模块说明

### 3.1 MainActivity — 主界面（核心）

**职责**: 全屏 Kiosk 界面 + Lock Task 管理 + DPI 控制 + 底部导航栏 + 退出逻辑

**关键常量**:

```java
APP_VERSION     = "1.0.7"   // 页面显示版本号，用于确认更新是否生效
DPI_DEFAULT     = 240        // 隐藏导航栏时的 DPI
DPI_NAVBAR      = 200        // 显示导航栏时的 DPI
```

**DPI 切换机制（核心特性）**:

> Android 设备导航栏的显示/隐藏高度依赖屏幕密度（DPI）。高 DPI 下导航栏被挤占无法显示，降低 DPI 可为其腾出空间。

```
显示导航栏: wm density 200 → exitLockTask() → clearFlags(FLAG_FULLSCREEN) → controller.show()
隐藏导航栏: wm density 240 → FLAG_FULLSCREEN → controller.hide()
```

**锁定策略（v1.0.7 关键改动）**:

- `setupLockTaskPackages()`: 通过 DPM 将本应用加入 Lock Task 白名单
- **不调用** `startLockTask()`: 避免系统弹出"应用已固定"提示框
- Device Owner 权限已足够阻止 Home/Recent 键退出

**底部导航栏（5 个按钮）**:

| 按钮 | 功能 |
|------|------|
| 返回 | WebView 后退 |
| 主页 | 重新加载当前页面 |
| 刷新 | 重新加载 / Activity recreate |
| 显示/隐藏导航栏 | 切换系统导航栏 + DPI |
| 退出 | 3秒内连点5次退出（防误触）|

**全屏守护保活机制**:

```java
mKeepFullscreenTask: Handler 每 500ms 循环检查
    → 仅在 mSystemBarsHidden==true 时调用 hideSystemUI()
    → 防止系统意外恢复状态栏/导航栏
```

### 3.2 KioskDeviceAdminReceiver — 设备管理器

- 继承 `DeviceAdminReceiver`
- 激活时自动将 `com.kiosk.app` 加入 Lock Task 白名单
- 引用 `device_admin.xml` 声明设备管理策略

### 3.3 BootReceiver — 开机自启

- 监听 `BOOT_COMPLETED` 广播
- 自动启动 MainActivity，确保断电重启后自动恢复 Kiosk

### 3.4 布局 (activity_main.xml)

```
┌─────────────────────────────────┐
│  FrameLayout (WebView + 状态文本) │  ← android:keepScreenOn="true"
│         "Kiosk 模式已启动"         │
│         "版本: 1.0.7"             │
├─────────────────────────────────┤
│ 返回 │ 主页 │ 刷新 │ 导航栏 │ 退出 │  ← 56dp 底部导航栏
└─────────────────────────────────┘
```

- WebView 默认隐藏（`visibility="gone"`），待加载 Web 应用
- 退出按钮红色样式，其余灰色

---

## 4. 构建与签名

### 4.1 本地构建

```bash
# 环境要求
- JDK 17
- Android SDK (compileSdk 33, build-tools 33)

# 构建 Release APK
./gradlew assembleRelease

# 输出路径
app/build/outputs/apk/release/app-release-unsigned.apk
```

### 4.2 CI/CD 自动构建（GitHub Actions）

- **触发条件**: push 到 `main` / `master` 分支，或手动触发
- **流程**: Checkout → JDK 17 → `assembleRelease` → 签名 → 上传构建产物
- **签名**: 使用自动生成的 debug 密钥（keystore），生产环境需替换为正式密钥
- **产物下载**: Actions 页面 → Artifacts → `KioskAndroid-APK`

---

## 5. 部署指南

### 5.1 首次部署

```bash
# 1. 安装 APK
adb install app-release.apk

# 2. 设为设备所有者（关键步骤！）
adb shell dpm set-device-owner com.kiosk.app/.KioskDeviceAdminReceiver

# 3. 启动应用
adb shell am start -n com.kiosk.app/.MainActivity
```

### 5.2 版本更新（设备已安装旧版）

```bash
# 方法1: 使用自动化脚本
update_kiosk.bat

# 方法2: 手动执行
adb root
adb shell "rm /data/system/device_owner_2.xml /data/system/device_policies.xml"
adb reboot
# 等待设备重启...
adb uninstall com.kiosk.app
adb install app-release.apk
adb shell dpm set-device-owner com.kiosk.app/.KioskDeviceAdminReceiver
```

> **注意**: 由于应用是 Device Owner，无法直接卸载。必须先清除设备所有者配置文件再重启。

### 5.3 设为主屏幕（可选）

将应用设为默认桌面，开机直接进入 Kiosk：

```bash
adb shell cmd package set-home-activity com.kiosk.app/.MainActivity
```

恢复默认桌面：

```bash
adb shell cmd package set-home-activity com.android.launcher3/.Launcher
```

---

## 6. 版本历史

| 版本 | 关键改动 |
|------|----------|
| 1.0.0 | 初始版本，基础 Kiosk 锁定 |
| 1.0.1 | 添加系统导航栏显示/隐藏按钮 |
| 1.0.2 | 尝试 lockTask 切换修复导航栏显示 |
| 1.0.3 | 延迟 + setDecorFitsSystemWindows 修复 |
| 1.0.4 | 版本号显示 + FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS |
| 1.0.5 | **DPI 动态切换（240↔200）+ 导航栏显示问题彻底修复** |
| 1.0.6 | 代码精简，移除无效的延迟回调 |
| 1.0.7 | **移除 startLockTask() 避免系统提示框** |

---

## 7. 关键技术决策

### 7.1 为什么用 DPI 切换而非纯 API 控制导航栏？

Lock Task Mode 会强制抑制系统导航栏，`WindowInsetsController.show()` 在 Lock Task 下无效。降低 DPI 是唯一可靠方式。

### 7.2 为什么不调用 startLockTask()？

`startLockTask()` 会触发 Android 系统弹出"应用已固定"提示框，遮挡底部按钮。DPM `setLockTaskPackages`（Device Owner 策略）已足够阻止用户退出。

### 7.3 为什么用 Runtime.exec("wm density") 而非 Settings API？

`wm density` 命令即时生效且作用于全局，`Settings.Secure` 写入在某些 ROM 上不立即生效。

---

## 8. 已知问题与注意事项

| 问题 | 说明 |
|------|------|
| 导航栏依赖 DPI | 不同设备/屏幕尺寸可能需要调整 DPI_NAVBAR 值 |
| 设备所有者限制 | 每台设备只能有一个 Device Owner，卸载需先清除配置 |
| APK 签名 | 调试/升级时必须使用同一签名，否则需要先卸载 |
| minSdk 30 | 仅支持 Android 11 及以上系统 |
| WebView 未加载 | 当前 WebView 组件已预留但未设置 URL，需根据实际需求加载 |

---

## 9. 开发规范

- **版本号**: 每次修改后递增 `APP_VERSION` 常量，确保设备上可确认版本
- **提交信息**: 使用 `feat:` / `fix:` / `refactor:` 前缀
- **分支**: 当前直接在 `master` 分支开发
- **签名密钥**: 生产环境需替换 `android-build.yml` 中的 debug 签名逻辑

---

*文档维护: 与代码同步更新，当前对应版本 1.0.7*
