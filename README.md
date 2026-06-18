# SKB Java SDK

这是基于当前固件公共协议实现的 Java 操作库。SDK 初始化只需要传入串口设备名，默认串口参数为 `9600 8 N 1`。

## 协议格式

请求帧和响应帧格式：

```text
设备地址(1) 功能码(1) 序号(1) 数据长度(1) 数据(N) 校验(1)
```

校验算法与固件 `Checksum_Sum` 一致：对校验前全部字节求和，校验字节取补码，使整帧累加低 8 位为 0。

## 使用示例

本 SDK 的 `pom.xml` 已声明 `jSerialComm` 运行时依赖。如果直接复制源码到其它工程，需在 classpath 中提供 `jSerialComm`，例如 Maven 项目中添加：

```xml
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <version>2.11.0</version>
</dependency>
```

示例：

```java
import com.wx.skb.SkbClient;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (SkbClient skb = new SkbClient("COM3")) {
            int timeoutMillis = SkbClient.DEFAULT_TIMEOUT_MILLIS;
            skb.setCommandIntervalMillis(50);
            skb.openLock(1, timeoutMillis);
            skb.keepOpen(2, timeoutMillis);
            skb.keepClose(2, timeoutMillis);
            skb.delayClose(3, 20, timeoutMillis); // 20 * 50ms = 1s
        }
    }
}
```

Linux 串口设备名通常类似 `/dev/ttyUSB0`，Windows 通常类似 `COM3`。

## RS485 并发与命令间隔

`SkbClient` 内部会把一次完整的请求/应答作为临界区处理。多个线程共用同一个 `SkbClient` 实例时，SDK 会自动串行化访问串口，避免 RS485 总线上同时写入多条命令。

带应答命令需要传入本次等待响应的超时时间，SDK 提供默认值常量 `SkbClient.DEFAULT_TIMEOUT_MILLIS = 150`。默认上一条命令收到应答或超时返回后，至少再等待 `50ms` 才会发送下一条命令。可通过 `setCommandIntervalMillis(ms)` 调整命令返回后的等待时间；设置为 `0` 表示不额外等待。

## 主要 API

- `openLock(lockId, timeoutMillis)`：瞬时开锁。
- `keepOpen(lockId, timeoutMillis)`：常开。
- `keepClose(lockId, timeoutMillis)`：常关。
- `delayClose(lockId, delayTicks50ms, timeoutMillis)`：延时关闭，延时单位为 50ms。
- `getDoorStatus(lockId, timeoutMillis)`：读取门状态表，返回起始锁号、结束锁号和状态数组。
- `getDoorStatus(requestDeviceAddress, lockId, timeoutMillis)`：指定请求地址读取门状态，适合 RS485 总线上按板轮询。
- `getAllDoorStatus(boardProbeLockIds, timeoutMillis)`：按每块锁板的代表锁号逐个读取所有锁板门状态。
- `getAllDoorStatus(timeoutMillis)`：从 1 号锁开始读取所有锁板门状态，每次使用上一块板返回的结束锁号 `endLockId + 1` 查询下一块板，直到查询超时结束。
- `getAllDoorStatus(firstLockId, timeoutMillis)`：从指定锁号开始执行同样的动态轮询。
- `existCheck(lockId, timeoutMillis)`：存在检测，返回起始锁号、结束锁号和状态数组。
- `setStartLockId(lockId)`：设置起始锁号。当前固件该命令不返回 ACK。
- `setEndLockId(lockId, timeoutMillis)`：设置结束锁号。
- `setAddressRange(startId, endId, timeoutMillis)`：一次性设置起止锁号。
- `setChannelMap(logicalLockId, physicalChannel, timeoutMillis)`：设置逻辑锁号到实际通道的映射，编号从 1 开始。

默认请求设备地址为 `1`。如现场协议需要其它非 0 地址，可调用 `setDeviceAddress(address)`。

读取所有锁板门状态示例：

```java
// 从 1 号锁开始，查到一块板后用该板返回的 endLockId + 1 继续查下一块板。
// 下一块板无响应超时后结束，已读取到的结果会正常返回。
int timeoutMillis = SkbClient.DEFAULT_TIMEOUT_MILLIS;
List<SkbClient.StatusResult> all = skb.getAllDoorStatus(timeoutMillis);

// 如果不是从 1 号锁开始：
List<SkbClient.StatusResult> allFrom13 = skb.getAllDoorStatus(13, timeoutMillis);

// 已知每块板代表锁号时，也可以显式指定。
List<SkbClient.StatusResult> selected = skb.getAllDoorStatus(Arrays.asList(1, 13, 37), timeoutMillis);
```

## 测试程序

项目提供了一个命令行测试程序：

```text
src/test/java/com/wx/skb/SkbClientTestProgram.java
```

无硬件时可先跑 mock 测试：

```bash
mvn test-compile
java -cp target/classes:target/test-classes com.wx.skb.SkbClientTestProgram --mock --action all-status
```

Windows PowerShell 下 classpath 分隔符使用分号：

```powershell
java -cp "target/classes;target/test-classes" com.wx.skb.SkbClientTestProgram --mock --action all-status
```

连接真实串口测试：

```powershell
java -cp "target/classes;target/test-classes;%USERPROFILE%\.m2\repository\com\fazecast\jSerialComm\2.11.0\jSerialComm-2.11.0.jar" com.wx.skb.SkbClientTestProgram --port COM3 --action status --lock 1
java -cp "target/classes;target/test-classes;%USERPROFILE%\.m2\repository\com\fazecast\jSerialComm\2.11.0\jSerialComm-2.11.0.jar" com.wx.skb.SkbClientTestProgram --port COM3 --action open --lock 1
```

常用动作：`status`、`all-status`、`open`、`keep-open`、`keep-close`、`delay-close`、`exist-check`、`set-start`、`set-end`、`set-range`、`map`。
