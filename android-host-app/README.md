# SKB Android测试上位机

这是面向工业 Android 设备的测试上位机 App，直接使用设备原生串口节点，例如 `/dev/ttyS*`、`/dev/ttySAC*`、`/dev/ttyAMA*`、`/dev/ttymxc*`。

App 会通过 Gradle 模块依赖引入本地 `skb-sdk`，构建宿主 APK 时会自动编译 SDK：

```text
:skb-sdk
```

对外发布或给其他项目使用时，再单独生成 `skb-sdk-release.aar`。

## 功能

- 启动页输入门号并调用公开的 `SkbSdk.openLock` 接口。
- 启动页可打开或关闭固定的 `/dev/ttyS5` 串口连接。
- 从启动页打开原生 SDK 调试面板查看底层串口调试信息。
- 枚举 `/dev` 下常见串口设备，并通过下拉框选择。
- 串口参数默认 `9600 8N1`。
- 支持查门状态、查全部锁板、瞬时开锁、常开、常关、延时关。
- JNI 使用 Linux termios 打开原生串口，不依赖 USB 串口库。

## Android Studio 打开

用 Android Studio 打开：

```text
java-sdk/android-host-app
```

本机 SDK 路径已写入：

```properties
sdk.dir=D\:\\ProgramData\\Android\\Sdk
```

## 设备权限

工业 Android 设备需要允许 App 读写串口设备节点。如果连接失败并提示 `Permission denied`，需要在系统侧处理串口权限，例如由设备固件、init 脚本、系统签名权限或调试阶段手动 chmod/chown 配置。

示例设备节点：

```text
/dev/ttyS0
/dev/ttyS1
/dev/ttyS3
/dev/ttySAC0
/dev/ttyAMA0
/dev/ttymxc1
```
