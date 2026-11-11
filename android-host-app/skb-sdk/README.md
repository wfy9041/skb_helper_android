# SKB Android AAR 接入

`skb-sdk` 将 Java 通信协议、原生串口 `.so` 和人工调试界面放进同一个 AAR。
SDK 在宿主进程内管理一个串口连接。宿主只需初始化和调用方法，无需另行安装调试 APK。

当前 SDK 版本：`1.2.0`。对外业务接口统一通过 `com.wx.skb.hostapp.SkbSdk` 调用；`com.wx.skb.SkbClient` 是协议核心类，宿主使用 AAR 时不需要自行创建它。

## 引入

本仓库的 `app` 直接引用 `app/libs/skb-sdk-release.aar`，可作为独立调试 APK。
其他项目也可将生成的 `skb-sdk-release.aar` 放进应用模块 `libs`：

```groovy
dependencies {
    implementation files('libs/skb-sdk-release.aar')
}
```

最低 Android API 21。库无额外第三方运行时依赖；AAR 包含 SDK 的 Java 类，不能再重复引入旧版 SDK JAR。
库 Manifest 自动合并调试 Activity，默认不向其他应用导出，使用独立的 `SkbDebugTheme`。
JNI 类名保护规则随 AAR 的 consumer rules 一起生效。
宿主和调试界面必须在同一进程，不要为这个 Activity 单独设置 `android:process`。

## 初始化与连接生命周期

```java
import com.wx.skb.SkbClient;
import com.wx.skb.hostapp.SkbSdk;

SkbSdk.init(getApplicationContext(), "/dev/ttyS3", new SkbSdk.Callback<Void>() {
    @Override public void onSuccess(Void ignored) {
        // 初次连接成功，可以启用业务操作。
    }
    @Override public void onError(Exception error) {
        // 初次连接失败。通过 Listener 观察后续连接恢复。
        android.util.Log.e("SKB", "初始化失败", error);
    }
});

SkbSdk.openLock(1, new SkbSdk.Callback<SkbSdk.DoorResult>() {
    @Override public void onSuccess(SkbSdk.DoorResult result) {
        // SDK 已解析锁板反馈，宿主无需处理 ACK 或原始数据。
        android.util.Log.i("SKB", "门号=" + result.getLockId() + "，已打开=" + result.isOpen());
    }
    @Override public void onError(Exception error) {
        if (error instanceof SkbSdk.SdkException) {
            String code = ((SkbSdk.SdkException) error).getCode();
            // DEBUG_BUSY：人工调试中；NOT_CONNECTED：尚未连接或正在恢复。
            android.util.Log.w("SKB", code);
        } else {
            // 包括锁板业务失败、响应超时、串口 I/O 和协议校验错误。
            android.util.Log.e("SKB", "开锁结果未确认", error);
        }
    }
});
```

`init` 的 `devicePath` 必须是以 `/dev/` 开头的设备节点，例如 `/dev/ttyS3`、`/dev/ttySAC0`。
所有结果回调、Listener 通知都在主线程，收发和连接操作在 SDK 专用线程串行执行。
初始化结果只回调一次；连接失败后每 2 秒尝试重新打开串口，恢复结果通过 Listener 通知。
同一路径已连接时重复初始化不会重新打开；初始化不同路径会关闭旧连接后打开新路径。
默认波特率 9600，命令间隔 100ms，响应读取超时参数 800ms；`openLock` 也有自定义超时重载。
现有协议分头部和尾部读取，各次读取使用传入的超时参数，它不是整个排队加执行的总时限。
SDK 不保存 Activity Context。进程被系统终止后串口句柄由系统释放，下次进程启动需要再次初始化。

建议在 `Application` 或首个业务页面创建时初始化，并根据 `getState()` / Listener 决定是否启用按钮或轮询：

```java
SkbSdk.init(getApplicationContext(), "/dev/ttyS3", new SkbSdk.Callback<Void>() {
    @Override public void onSuccess(Void value) {
        // 仅表示串口已打开；此时可以发起 SKB 命令。
    }
    @Override public void onError(Exception error) {
        // 首次打开失败；SDK 仍会自动重连。
    }
});

SkbSdk.State state = SkbSdk.getState();
String path = SkbSdk.getDevicePath();
```

状态含义：`DISCONNECTED` 未连接，`CONNECTING` 正在连接，`CONNECTED` 可执行命令，`RECONNECTING` 连接异常且等待自动重连。
调用 `shutdown` 会关闭串口并取消自动重连，之后可以再次调用 `init`。

## 业务接口

所有业务方法都是异步方法，最后一个参数是必填回调。除特别注明外，命令使用默认响应超时 800ms；命令按调用顺序串行排队。

### 开关门

```java
SkbSdk.openLock(int lockId, Callback<SkbSdk.DoorResult> callback);
SkbSdk.openLock(int lockId, int timeoutMillis, Callback<SkbSdk.DoorResult> callback);
SkbSdk.keepOpen(int lockId, Callback<SkbSdk.DoorResult> callback);
SkbSdk.keepClose(int lockId, Callback<SkbSdk.DoorResult> callback);
SkbSdk.delayClose(int lockId, long delayTicks50ms, Callback<SkbSdk.DoorResult> callback);
```

`openLock` 为瞬时开锁，`keepOpen` / `keepClose` 分别设置常开 / 常关，`delayClose` 的延时单位是 50ms（例如 `20` 约等于 1 秒）。成功结果为 `DoorResult`：

```java
public int getLockId();       // 锁板反馈的锁号
public int getStatus();       // 1=打开，0=关闭
public boolean isOpen();      // getStatus() == 1
public byte[] getRequestFrame();  // 完整请求帧副本
public byte[] getResponseFrame(); // 完整响应帧副本
```

SDK 会校验反馈锁号和门状态；响应不符合预期时回调失败，不会把它当作成功开锁。

### 状态与存在查询

```java
SkbSdk.getDoorStatus(int lockId, Callback<SkbClient.StatusResult> callback);
SkbSdk.getAllDoorStatus(Callback<List<SkbClient.StatusResult>> callback);
SkbSdk.existCheck(int lockId, Callback<SkbClient.StatusResult> callback);
```

需要导入 `java.util.List` 和 `com.wx.skb.SkbClient`。`StatusResult` 的含义如下：

```java
int start = result.getStartLockId();
int end = result.getEndLockId();
byte[] status = result.getStatus();
// status[0] 对应 start，status[i] 对应 start + i，直到 end。
byte[] request = result.getRequestFrame();
byte[] response = result.getResponseFrame();
```

状态字节按协议原样返回，数组长度通常为 `end - start + 1`。`getAllDoorStatus` 从 1 号锁开始，按上一块锁板返回的 `endLockId + 1` 动态查询后续锁板；没有下一块板响应时结束并返回已取得的结果。

例如打印所有门状态：

```java
SkbSdk.getAllDoorStatus(new SkbSdk.Callback<List<SkbClient.StatusResult>>() {
    @Override public void onSuccess(List<SkbClient.StatusResult> results) {
        for (SkbClient.StatusResult board : results) {
            byte[] states = board.getStatus();
            for (int i = 0; i < states.length; i++) {
                int lockId = board.getStartLockId() + i;
                int value = states[i] & 0xff;
                android.util.Log.i("SKB", "lock=" + lockId + ", status=" + value);
            }
        }
    }
    @Override public void onError(Exception error) { /* 处理协议、超时或串口错误 */ }
});
```

### 地址、通道与设备地址

```java
SkbSdk.setAddressRange(int start, int end, Callback<SkbClient.Ack> callback);
SkbSdk.setStartLockId(int lockId, Callback<Void> callback);
SkbSdk.setEndLockId(int lockId, Callback<SkbClient.Ack> callback);
SkbSdk.setChannelMap(int lockId, int channel, Callback<SkbClient.Ack> callback);
SkbSdk.setDeviceAddress(int address, Callback<Void> callback);
```

`setStartLockId` 按当前协议不等待 ACK，因此成功只表示命令已发送；其余设置方法成功表示收到并校验了 ACK。
请求设备地址默认为 `1`，`setDeviceAddress` 设置的值会在自动重连后继续使用。

`SkbClient.Ack` 提供以下结果字段，适合需要检查自定义返回数据的场景：

```java
int function = ack.getFunction();
int sequence = ack.getSequence();
int resultCode = ack.getResult(); // 数据区第 2 字节；不足时为 -1
byte[] data = ack.getData();
byte[] request = ack.getRequestFrame();
byte[] response = ack.getResponseFrame();
```

## 自定义协议命令

```java
SkbSdk.sendRaw(int function, byte[] data, int timeoutMillis,
               Callback<SkbClient.Ack> callback);
SkbSdk.sendRawNoAck(int function, byte[] data, Callback<Void> callback);
```

`sendRaw` 会按现有 SKB 协议封装功能码、序号、长度和校验，并等待 ACK；`sendRawNoAck` 只发送、不等待响应。`data` 会在调用时复制，调用方可以安全复用或修改原数组。两者都不会自动重发。

## 错误处理与并发规则

失败通过 `Callback.onError(Exception)` 返回，常见情况包括：

- `SkbSdk.SdkException`：通过 `getCode()` 获取机器可判断的错误码。常见值为 `NOT_CONNECTED`（尚未连接或正在恢复）、`DEBUG_BUSY`（人工调试接管中）、`DEBUG_CLOSED`（调试会话已结束）和 `INVALID_BOARD_RESPONSE`（锁板反馈格式或内容不合法）。
- `SerialTimeoutException`：在规定时间内没有收到完整响应。超时后的命令结果未知，SDK 不会重放该命令。
- `IOException`：串口读写失败。SDK 会关闭当前连接并启动自动重连，但不会重放失败命令。

同一个 `SkbSdk` 是进程级共享入口，SDK 内部保证串口读写和命令排队串行。不要并行创建 `SkbClient`、`NativeSerialTransport`，或从宿主直接打开同一串口。进入人工调试后，新的业务调用会失败并返回 `DEBUG_BUSY`；退出调试后才恢复业务调用。

## 人工调试

```java
SkbSdk.openDebug(activity);
```

无需重复传串口路径，调试界面直接使用已有连接。尚未初始化时也可进入界面，手动选择串口开启。
进入界面时先阻止新业务调用，等待当前正在执行的命令完成或超时，再允许人工操作。
尚未执行的业务队列和调试期间的新业务调用返回 `DEBUG_BUSY`，不会保存到退出后补发。
界面重建保留同一调试会话；返回按钮或系统返回销毁界面后，已接收的调试操作执行完再恢复业务。
退出界面本身不关闭串口，也不重连。可用 `SkbSdk.isDebugging()` 判断是否可以恢复轮询。
在调试界面主动关闭或切换串口会改变共享连接；退出后宿主继续使用该连接状态和路径。

```java
SkbSdk.Listener listener = (state, path, message) -> {
    boolean businessReady = state == SkbSdk.State.CONNECTED && !SkbSdk.isDebugging();
    // 根据 businessReady 更新操作按钮或轮询状态；勿自动补发失败的开锁命令。
    android.util.Log.i("SKB", state + " " + path + " " + message);
};
SkbSdk.addListener(listener);
// 页面销毁或不再监听时：
SkbSdk.removeListener(listener);
```

`getState()` 返回 `DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING`。
Listener 包含连接、收发日志和调试进入/退出事件。注册不会自动推送快照，首次读取 `getState()` 即可。
`isDebugging()` 在接管等待期间也为 true。

调用 `SkbSdk.shutdown(callback)` 可彻底停用：关闭串口、取消自动重连；之后仍可以重新初始化。该方法不会清除监听器。

使用托管接口时不要再自行创建 `SkbClient` 或 `NativeSerialTransport` 打开同一串口。
业务指令和调试指令均不自动重发。响应超时不会触发串口重开；I/O 故障会恢复连接，但不会重放失败指令。
设备节点读写权限和宿主 APK 的 CPU ABI 必须匹配实际设备；AAR 不授予额外系统权限。当前 Native 串口库应随 AAR 按宿主 ABI 打包，若出现 `UnsatisfiedLinkError`，优先检查设备 ABI 和 APK/AAR 中的 `lib/<abi>/libskb_serial.so`。

## 独立 APK 兼容

仍支持 `serial_port`、`source`、`caller_package` 启动参数。
由于串口改为 SDK 持有，返回应用时 `return_status` 改为 `debug_finished`，不再声称 `serial_closed`。
独立 APK 的 Activity 由 app Manifest 显式导出，嵌入其他宿主时默认不导出。

## 构建与验证

在 `android-host-app` 目录执行宿主构建：

```powershell
./gradlew.bat :app:assembleDebug --offline --stacktrace
```

宿主直接消费 `app/libs/skb-sdk-release.aar`，产物为 `app/build/outputs/apk/debug/app-debug.apk`。
调试界面、Native 串口封装和 JNI 文件均位于 `skb-sdk/src/main`，app 不再持有或编译 SDK 实现源码。
更新 SDK 时先生成新的 `skb-sdk-release.aar`，再替换 `app/libs/skb-sdk-release.aar`；发布时只需分发 AAR，不需要把这些源码目录复制到宿主。

无需 Gradle 的 Java 编译与模拟串口回归测试：

```powershell
./skb-sdk/test-session.ps1
```

脚本使用本机 JDK 和 Android 33 的 android.jar 编译实际 Java 源码，在 JVM 上运行管理器测试。
覆盖初始化幂等、超时不重发、调试互斥、排队指令拒绝、退出保留连接、重连不重放和停用取消重连。
这不代表 AAR 打包、Manifest 合并、JNI 加载或设备串口行为已验证。
