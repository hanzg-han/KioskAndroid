# KioskAndroid - 自助终端锁屏应用

## 项目概述

基于 Android 的自助 Kiosk 终端应用，具备以下核心功能：

-   **全屏沉浸模式**：隐藏状态栏和导航栏
-   **屏幕锁定**：通过 Lock Task Mode 锁定界面，禁止退出、禁止下拉通知栏
-   **横屏显示**：固定横屏模式
-   **设备所有者模式**：作为 Device Owner 获得完整 Kiosk 控制权

---

## 1. 编译构建（GitHub Actions）

### 自动构建
推送代码到 `master` 分支后，GitHub Actions 自动编译并生成 APK。

### 手动触发
1.  打开 [Actions 页面](https://github.com/hanzg-han/KioskAndroid/actions)
2.  点击左侧 **Android CI**
3.  点击 **Run workflow** → **Run workflow**
4.  构建完成后，点击构建记录 → **Artifacts** → 下载 `KioskAndroid-APK`

---

## 2. 安装部署到设备

### 前提条件
-   ADB 工具已安装
-   USB 调试已开启（或通过网络 ADB 连接）
-   **重要**：执行 `set-device-owner` 前，先移除设备上的所有账户（设置 → 账户 → 移除所有账户）

### 步骤

```bash
# 1. 安装应用
adb install app-release.apk

# 2. 设置设备所有者（关键步骤，只需执行一次）
adb shell dpm set-device-owner com.kiosk.app/.KioskDeviceAdminReceiver

# 3. 设为默认桌面
adb shell cmd package set-home-activity com.kiosk.app/.MainActivity

# 4. 重启设备
adb reboot
```

启动后设备自动进入 Kiosk 全屏锁定状态，**返回键、Home 键、状态栏下拉均被禁用**。

---

## 3. 退出 Kiosk 模式（调试）

```bash
# 方法1: 取消设备所有者
adb shell dpm remove-active-admin com.kiosk.app/.KioskDeviceAdminReceiver

# 方法2: 从 Launcher 列表移除（恢复默认桌面）
adb shell cmd package set-home-activity --user 0 com.android.launcher/.LauncherActivity

# 方法3: 清除应用数据
adb shell pm clear com.kiosk.app

# 方法4: 完全卸载
adb shell pm uninstall com.kiosk.app
```

---

## 4. 项目结构

```
KioskAndroid/
├── .github/workflows/
│   └── android-build.yml        # GitHub Actions 自动构建 + 签名
├── app/
│   ├── src/main/
│   │   ├── java/com/kiosk/app/
│   │   │   ├── MainActivity.java              # Kiosk 主界面
│   │   │   └── KioskDeviceAdminReceiver.java  # 设备管理器接收器
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/themes.xml
│   │   │   ├── values/strings.xml
│   │   │   └── xml/device_admin.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
└── gradle/wrapper/
```

---

## 5. 技术说明

| 组件 | 说明 |
|------|------|
| Lock Task Mode | Android 5.0+ 屏幕固定模式，`startLockTask()` + `lockTaskMode="if_whitelisted"` |
| Device Owner | 通过 `dpm set-device-owner` 设置，获得完整 Kiosk 控制权 |
| 沉浸模式 | `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 隐藏系统栏 |
| 编译环境 | JDK 17, Gradle 8.2, AGP 8.2.0, compileSdk 33, minSdk 30 |
