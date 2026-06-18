# AGENTS.md

## 项目本机工具链

本项目当前在 Windows 环境下开发，工作目录：

```text
D:\git\skb
```

## Android Studio

Android Studio 安装目录：

```text
D:\Program Files\Android\Android Studio
```

Android Studio 自带 JDK：

```text
D:\Program Files\Android\Android Studio\jbr
```

`javac.exe` 路径：

```text
D:\Program Files\Android\Android Studio\jbr\bin\javac.exe
```

## Android SDK

Android SDK 目录：

```text
D:\ProgramData\Android\Sdk
```

当前 `java-sdk/android-host-app/local.properties` 已配置：

```properties
sdk.dir=D\:\\ProgramData\\Android\\Sdk
```

已确认存在的 SDK platforms：

```text
android-22
android-28
android-29
android-30
android-31
android-32
android-33
android-34
```

Android 上位机工程当前使用：

```text
compileSdk 34
targetSdk 34
minSdk 23
```

已确认存在的 build-tools：

```text
29.0.2
29.0.3
30.0.2
30.0.3
33.0.0
34.0.0
```

## Android NDK / CMake

已确认存在的 NDK：

```text
D:\ProgramData\Android\Sdk\ndk\21.0.6113669
D:\ProgramData\Android\Sdk\ndk\21.1.6352462
```

Android 上位机工程当前固定使用：

```gradle
ndkVersion "21.1.6352462"
```

已确认存在的 CMake：

```text
D:\ProgramData\Android\Sdk\cmake\3.10.2.4988404
```

Android 上位机工程当前使用：

```gradle
version "3.10.2"
```

## Gradle / Android Gradle Plugin

本机 Gradle wrapper 缓存中已确认存在：

```text
C:\Users\36907\.gradle\wrapper\dists\gradle-6.5-bin\6nifqtx7604sqp1q6g8wikw7p\gradle-6.5\bin\gradle.bat
C:\Users\36907\.gradle\wrapper\dists\gradle-7.2-bin\2dnblmf4td7x66yl1d74lt32g\gradle-7.2\bin\gradle.bat
C:\Users\36907\.gradle\wrapper\dists\gradle-7.3.3-bin\6a41zxkdtcxs8rphpq6y0069z\gradle-7.3.3\bin\gradle.bat
C:\Users\36907\.gradle\wrapper\dists\gradle-8.0-bin\ca5e32bp14vu59qr306oxotwh\gradle-8.0\bin\gradle.bat
C:\Users\36907\.gradle\wrapper\dists\gradle-9.1.0-bin\9agqghryom9wkf8r80qlhnts3\gradle-9.1.0\bin\gradle.bat
```

Android 上位机工程已用以下 Gradle 离线构建成功：

```text
C:\Users\36907\.gradle\wrapper\dists\gradle-8.0-bin\ca5e32bp14vu59qr306oxotwh\gradle-8.0\bin\gradle.bat
```

本机 Gradle 缓存中已确认存在的 Android Gradle Plugin：

```text
7.0.4
7.2.1
8.0.2
```

Android 上位机工程当前使用：

```gradle
id "com.android.application" version "8.0.2" apply false
```

## Android 上位机工程

工程目录：

```text
D:\git\skb\java-sdk\android-host-app
```

已验证构建命令：

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.0-bin\ca5e32bp14vu59qr306oxotwh\gradle-8.0\bin\gradle.bat" assembleDebug --offline --stacktrace
```

已生成 APK：

```text
D:\git\skb\java-sdk\android-host-app\app\build\outputs\apk\debug\app-debug.apk
```

## 注意事项

- 工业 Android 设备使用原生串口节点，例如 `/dev/ttyS*`、`/dev/ttySAC*`、`/dev/ttyAMA*`、`/dev/ttymxc*`。
- App 内串口选择为枚举下拉框，不使用手写默认串口名。
- 如果设备运行时报 `Permission denied`，需要系统侧给 App 读写对应 `/dev/tty*` 节点的权限。
- 未经确认，不删除文件、目录、依赖、资源文件、配置文件、脚本或测试文件。
