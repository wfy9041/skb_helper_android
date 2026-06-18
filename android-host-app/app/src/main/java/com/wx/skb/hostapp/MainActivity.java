package com.wx.skb.hostapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.wx.skb.SerialTransport;
import com.wx.skb.SkbClient;
import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.view.ViewGroup;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int DEFAULT_BAUD_RATE = 9600;
    private static final int DEFAULT_COMMAND_INTERVAL_MILLIS = 100;
    private static final int DEFAULT_RESPONSE_TIMEOUT_MILLIS = SkbClient.DEFAULT_TIMEOUT_MILLIS;
    private static final int LEGACY_COMMAND_INTERVAL_MILLIS = 500;
    private static final int CONTROL_HEIGHT_DP = 56;
    private static final int MAX_LOG_FRAME_LENGTH = 261;
    private static final String PREFS_NAME = "skb_host_app";
    private static final String PREF_DEVICE_PATH = "device_path";
    private Spinner deviceSpinner;
    private Button connectionButton;
    private EditText lockIdInput;
    private EditText startLockIdInput;
    private EditText endLockIdInput;
    private EditText delayTicksInput;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScrollView;
    private SkbClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        setConnected(false);
    }

    @Override
    protected void onDestroy() {
        closeClient();
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildContentView() {
        // 直接创建根布局（不再包裹在外层 ScrollView 中）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackground(panelBackground());
        // 确保根布局填满屏幕，使 weight 生效
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ----- 标题 -----
        TextView title = new TextView(this);
        title.setText("SKB测试上位机");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(20, 30, 45));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, matchWrap());

        // ----- 输入控件初始化 -----
        deviceSpinner = new Spinner(this);
        lockIdInput = input("1");
        lockIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        startLockIdInput = input("1");
        startLockIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        endLockIdInput = input("12");
        endLockIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        delayTicksInput = input("20");
        delayTicksInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        // ----- 串口行 -----
        LinearLayout serialRow = new LinearLayout(this);
        serialRow.setOrientation(LinearLayout.HORIZONTAL);
        serialRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView serialLabel = rowLabel("串口设备");
        connectionButton = button("开启", ButtonStyle.PRIMARY);
        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.rgb(70, 80, 95));
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        serialRow.addView(serialLabel, wrapWrap());
        serialRow.addView(deviceSpinner, rowSpinnerParams());
        serialRow.addView(statusView, statusParams());
        serialRow.addView(connectionButton, rowButtonParams());
        root.addView(serialRow, matchWrap());

        // ----- 分割线 -----
        root.addView(sectionLine(), sectionLineParams());

        // ----- 锁号行 -----
        LinearLayout lockHeader = new LinearLayout(this);
        lockHeader.setOrientation(LinearLayout.HORIZONTAL);
        lockHeader.setGravity(Gravity.CENTER_VERTICAL);
        lockHeader.addView(rowLabel("锁号"), wrapWrap());
        lockHeader.addView(lockIdInput, numberParams());
        lockHeader.addView(button("瞬时开锁", ButtonStyle.PRIMARY, openLockAction()), actionButtonParams());
        lockHeader.addView(button("查门状态", ButtonStyle.NORMAL, doorStatusAction()), actionButtonParams());
        lockHeader.addView(button("查全部", ButtonStyle.NORMAL, allDoorStatusAction()), actionButtonParams());
        root.addView(lockHeader, matchWrap());

        // ----- 分割线 -----
        root.addView(sectionLine(), sectionLineParams());

        // ----- 延时控制行 -----
        LinearLayout delayHeader = new LinearLayout(this);
        delayHeader.setOrientation(LinearLayout.HORIZONTAL);
        delayHeader.setGravity(Gravity.CENTER_VERTICAL);
        delayHeader.addView(rowLabel("延时 ticks"), wrapWrap());
        delayHeader.addView(delayTicksInput, numberParams());
        TextView tickHint = new TextView(this);
        tickHint.setText("1 tick = 50ms");
        tickHint.setTextSize(15);
        tickHint.setTextColor(Color.rgb(89, 101, 121));
        delayHeader.addView(tickHint, weightTextParams());
        delayHeader.addView(button("常开", ButtonStyle.NORMAL, keepOpenAction()), inlineButtonParams(false));
        delayHeader.addView(button("常关", ButtonStyle.DANGER, keepCloseAction()), inlineButtonParams(true));
        delayHeader.addView(button("延时关", ButtonStyle.DANGER, delayCloseAction()), inlineButtonParams(true));
        root.addView(delayHeader, matchWrap());

        // ----- 分割线 -----
        root.addView(sectionLine(), sectionLineParams());

        // ----- 地址范围行 -----
        LinearLayout addressRangeRow = new LinearLayout(this);
        addressRangeRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRangeRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRangeRow.addView(rowLabel("地址范围"), wrapWrap());
        addressRangeRow.addView(smallText("起始"), wrapWrap());
        addressRangeRow.addView(startLockIdInput, numberParams());
        addressRangeRow.addView(smallText("结束"), wrapWrap());
        addressRangeRow.addView(endLockIdInput, numberParams());
        addressRangeRow.addView(button("全开", ButtonStyle.PRIMARY, openAddressRangeAction()), inlineButtonParams(false));
        addressRangeRow.addView(button("配置地址", ButtonStyle.NORMAL, setAddressRangeAction()), separatedInlineButtonParams());
        addressRangeRow.addView(button("兼容配置", ButtonStyle.NORMAL, legacySetAddressRangeAction()), inlineButtonParams(true));
        root.addView(addressRangeRow, matchWrap());

        // ----- 分割线 -----
        root.addView(sectionLine(), sectionLineParams());

        // ----- 日志标题行 -----
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.addView(rowLabel("日志"), weightTextParams());
        logHeader.addView(button("清空", ButtonStyle.NORMAL, clearLogAction()), rowButtonParams());
        root.addView(logHeader, matchWrap());

        // ----- 日志内容（ScrollView + TextView）-----
        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextColor(Color.rgb(30, 38, 50));
        logView.setPadding(0, dp(12), 0, 0);

        logScrollView = new ScrollView(this);
        logScrollView.setVerticalScrollBarEnabled(true);
        logScrollView.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 关键：高度0，weight=1，占据剩余所有空间
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0);
        logParams.weight = 1;
        logScrollView.setLayoutParams(logParams);
        root.addView(logScrollView);

        // ----- 事件绑定 -----
        connectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (client == null) {
                    connect();
                } else {
                    closeClient();
                    setConnected(false);
                    appendLog("已断开");
                }
            }
        });

        refreshSerialPorts();
        return root;  // 直接返回根布局，不再包裹 ScrollView
    }

    private void refreshSerialPorts() {
        List<String> ports = findSerialPorts();
        int portCount = ports.size();
        if (ports.isEmpty()) {
            ports.add("未发现串口设备");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                ports);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        String savedDevicePath = savedDevicePath();
        if (savedDevicePath != null) {
            int savedIndex = ports.indexOf(savedDevicePath);
            if (savedIndex >= 0) {
                deviceSpinner.setSelection(savedIndex);
            }
        }
        appendLog("串口枚举: " + portCount + " 个");
    }

    private List<String> findSerialPorts() {
        File dev = new File("/dev");
        File[] files = dev.listFiles();
        List<String> result = new ArrayList<String>();
        if (files == null) {
            return result;
        }

        for (File file : files) {
            String name = file.getName();
            if (isSerialDeviceName(name)) {
                result.add(file.getAbsolutePath());
            }
        }
        Collections.sort(result);
        return result;
    }

    private boolean isSerialDeviceName(String name) {
        return name.matches("ttyS\\d+")
                || name.matches("ttySAC\\d+")
                || name.matches("ttyAMA\\d+")
                || name.matches("ttymxc\\d+")
                || name.matches("ttyMT\\d+")
                || name.matches("ttyHSL\\d+")
                || name.matches("ttyHS\\d+")
                || name.matches("ttyUSB\\d+")
                || name.matches("ttyACM\\d+");
    }

    private Runnable doorStatusAction() {
        return new Runnable() {
            @Override
            public void run() {
                withClient("查门状态", new ClientTask() {
                    @Override
                    public String run(SkbClient skb) throws Exception {
                        return formatStatus(skb.getDoorStatus(lockId(), 100));
                    }
                });
            }
        };
    }

    private Runnable allDoorStatusAction() {
        return new Runnable() {
            @Override
            public void run() {
                withClient("查全部", new ClientTask() {
                    @Override
                    public String run(SkbClient skb) throws Exception {
                        List<SkbClient.StatusResult> results =
                                skb.getAllDoorStatus(lockId(), 100);
                        if (results.isEmpty()) {
                            return "无锁板响应";
                        }
                        StringBuilder builder = new StringBuilder();
                        for (SkbClient.StatusResult result : results) {
                            if (builder.length() > 0) {
                                builder.append('\n');
                            }
                            builder.append(formatStatus(result));
                        }
                        return builder.toString();
                    }
                });
            }
        };
    }

    private Runnable openLockAction() {
        return new AckAction("瞬时开锁") {
            @Override
            SkbClient.Ack execute(SkbClient skb) throws Exception {
                return skb.openLock(lockId(), DEFAULT_RESPONSE_TIMEOUT_MILLIS);
            }
        };
    }

    private Runnable keepOpenAction() {
        return new AckAction("常开") {
            @Override
            SkbClient.Ack execute(SkbClient skb) throws Exception {
                return skb.keepOpen(lockId(), DEFAULT_RESPONSE_TIMEOUT_MILLIS);
            }
        };
    }

    private Runnable keepCloseAction() {
        return new AckAction("常关") {
            @Override
            SkbClient.Ack execute(SkbClient skb) throws Exception {
                return skb.keepClose(lockId(), DEFAULT_RESPONSE_TIMEOUT_MILLIS);
            }
        };
    }

    private Runnable delayCloseAction() {
        return new AckAction("延时关") {
            @Override
            SkbClient.Ack execute(SkbClient skb) throws Exception {
                return skb.delayClose(lockId(), intValue(delayTicksInput, 20), DEFAULT_RESPONSE_TIMEOUT_MILLIS);
            }
        };
    }

    private Runnable openAddressRangeAction() {
        return new Runnable() {
            @Override
            public void run() {
                withClient("全开", new ClientTask() {
                    @Override
                    public String run(SkbClient skb) throws Exception {
                        int start = startLockId();
                        int end = endLockId();
                        SkbClient.Ack lastAck = null;
                        int count = 0;
                        for (int lockId = start; lockId <= end; lockId++) {
                            lastAck = skb.openLock(lockId, DEFAULT_RESPONSE_TIMEOUT_MILLIS);
                            count++;
                        }
                        return "opened " + count + " locks, last ack: " + formatAck(lastAck);
                    }
                });
            }
        };
    }

    private Runnable setAddressRangeAction() {
        final Runnable confirmedAction = new AckAction("配置地址") {
            @Override
            SkbClient.Ack execute(SkbClient skb) throws Exception {
                return skb.setAddressRange(startLockId(), endLockId(), DEFAULT_RESPONSE_TIMEOUT_MILLIS);
            }
        };
        return new Runnable() {
            @Override
            public void run() {
                confirmAddressRangeAction("配置地址", confirmedAction);
            }
        };
    }

    private Runnable legacySetAddressRangeAction() {
        final Runnable confirmedAction = new Runnable() {
            @Override
            public void run() {
                withClient("兼容配置", new ClientTask() {
                    @Override
                    public String run(SkbClient skb) throws Exception {
                        int start = startLockId();
                        int end = endLockId();
                        skb.setStartLockId(start);
                        Thread.sleep(LEGACY_COMMAND_INTERVAL_MILLIS);
                        SkbClient.Ack endAck = skb.setEndLockId(end, DEFAULT_RESPONSE_TIMEOUT_MILLIS);
                        return "set-start sent, set-end ack: " + formatAck(endAck);
                    }
                });
            }
        };
        return new Runnable() {
            @Override
            public void run() {
                confirmAddressRangeAction("兼容配置", confirmedAction);
            }
        };
    }

    private void connect() {
        closeClient();
        final String devicePath = selectedDevicePath();
        if (devicePath == null) {
            appendLog("未发现可用串口，请检查设备节点权限");
            return;
        }
        final int baudRate = DEFAULT_BAUD_RATE;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    NativeSerialTransport transport = new NativeSerialTransport(devicePath, baudRate);
                    final SkbClient skb = new SkbClient(new LoggingSerialTransport(transport));
                    skb.setCommandIntervalMillis(DEFAULT_COMMAND_INTERVAL_MILLIS);
                    client = skb;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setConnected(true);
                            saveDevicePath(devicePath);
                            appendLog("已连接 " + devicePath + " @ " + baudRate);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setConnected(false);
                            appendLog("连接失败: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void withClient(final String name, final ClientTask task) {
        final SkbClient skb = client;
        if (skb == null) {
            appendLog("请先连接串口");
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String result = task.run(skb);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(name + " 成功: " + result);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(name + " 失败: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void closeClient() {
        SkbClient oldClient = client;
        client = null;
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (Exception ignored) {
                // Best effort close during UI teardown.
            }
        }
    }

    private void setConnected(boolean connected) {
        statusView.setText(connected ? "状态：已连接" : "状态：未连接");
        if (connectionButton != null) {
            connectionButton.setText(connected ? "关闭" : "开启");
            styleButton(connectionButton, connected ? ButtonStyle.DANGER : ButtonStyle.PRIMARY);
        }
        if (deviceSpinner != null) {
            deviceSpinner.setEnabled(!connected);
        }
    }

    private int lockId() {
        return intValue(lockIdInput, 1);
    }

    private int startLockId() {
        return intValue(startLockIdInput, 1);
    }

    private int endLockId() {
        int startLockId = startLockId();
        int endLockId = intValue(endLockIdInput, 12);
        if (endLockId < startLockId) {
            throw new IllegalArgumentException("结束地址不能小于起始地址");
        }
        return endLockId;
    }

    private String selectedDevicePath() {
        Object selected = deviceSpinner.getSelectedItem();
        if (selected == null) {
            return null;
        }
        String value = selected.toString();
        if (!value.startsWith("/dev/")) {
            return null;
        }
        return value;
    }

    private String savedDevicePath() {
        String value = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_DEVICE_PATH, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    private void saveDevicePath(String devicePath) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_DEVICE_PATH, devicePath)
                .apply();
    }

    private int intValue(EditText input, int defaultValue) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(text);
    }

    private String formatStatus(SkbClient.StatusResult status) {
        return "start=" + status.getStartLockId()
                + ", end=" + status.getEndLockId()
                + ", status=" + toHex(status.getStatus()).replaceAll("0(\\d)", "$1");
    }

    private String formatAck(SkbClient.Ack ack) {
        return "function=" + ack.getFunction()
                + ", sequence=" + ack.getSequence()
                + ", result=" + ack.getResult()
                + ", data=" + toHex(ack.getData());
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", data[i] & 0xff));
        }
        return builder.toString();
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("mm:ss.SSS ", Locale.US).format(new Date());
        logView.append("[" + time + "] " + message + "\n");
        if (logScrollView != null) {
            logScrollView.post(new Runnable() {
                @Override
                public void run() {
                    logScrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void appendLogFromAnyThread(final String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLog(message);
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                appendLog(message);
            }
        });
    }

    private Runnable clearLogAction() {
        return new Runnable() {
            @Override
            public void run() {
                logView.setText("");
                if (logScrollView != null) {
                    logScrollView.scrollTo(0, 0);
                }
            }
        };
    }

    private void confirmAddressRangeAction(final String title, final Runnable confirmedAction) {
        final int start;
        final int end;
        try {
            start = startLockId();
            end = endLockId();
        } catch (RuntimeException e) {
            appendLog(title + " 失败: " + e.getMessage());
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("确认对地址范围 " + start + " - " + end + " 执行" + title + "？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmedAction.run();
                    }
                })
                .show();
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(70, 80, 95));
        label.setPadding(0, dp(12), 0, dp(4));
        return label;
    }

    private TextView rowLabel(String text) {
        TextView label = label(text);
        label.setPadding(0, 0, dp(8), 0);
        return label;
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(89, 101, 121));
        view.setPadding(dp(8), 0, dp(6), 0);
        return view;
    }

    private EditText input(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextSize(16);
        input.setGravity(Gravity.CENTER);
        return input;
    }

    private LinearLayout fieldWithLabel(String labelText, EditText input) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(label(labelText), matchWrap());
        layout.addView(input, matchWrap());
        return layout;
    }

    private Button button(String text, ButtonStyle style) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        styleButton(button, style);
        return button;
    }

    private Button button(String text, ButtonStyle style, final Runnable action) {
        Button button = button(text, style);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return button;
    }

    private void styleButton(Button button, ButtonStyle style) {
        int borderColor = Color.rgb(216, 222, 232);
        int backgroundColor = Color.rgb(237, 242, 247);
        int textColor = Color.rgb(20, 32, 51);
        if (style == ButtonStyle.PRIMARY) {
            borderColor = Color.rgb(23, 105, 170);
            backgroundColor = Color.rgb(23, 105, 170);
            textColor = Color.WHITE;
        } else if (style == ButtonStyle.DANGER) {
            borderColor = Color.rgb(179, 38, 30);
            backgroundColor = Color.rgb(179, 38, 30);
            textColor = Color.WHITE;
        }
        button.setTextColor(textColor);
        button.setBackground(roundedDrawable(backgroundColor, borderColor, 6));
    }

    private void addAction(GridLayout grid, String title, ButtonStyle style, final Runnable action) {
        Button button = button(title, style, action);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(CONTROL_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(8), dp(4), dp(4));
        grid.addView(button, params);
    }

    private View sectionLine() {
        View view = new View(this);
        view.setBackgroundColor(Color.rgb(216, 222, 232));
        view.setMinimumHeight(dp(1));
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    private GradientDrawable panelBackground() {
        return roundedDrawable(Color.WHITE, Color.rgb(216, 222, 232), 8);
    }

    private GradientDrawable roundedDrawable(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private ScrollView.LayoutParams scrollContentParams() {
        ScrollView.LayoutParams params = new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(12), dp(24), dp(12), dp(24));
        return params;
    }

    private LinearLayout.LayoutParams numberParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(64),
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(0, dp(4), dp(8), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams sectionLineParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1));
        params.setMargins(0, dp(12), 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams logScrollParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260));
        params.setMargins(0, 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams inlineButtonParams(boolean leadingGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(leadingGap ? dp(8) : 0, dp(8), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams separatedInlineButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(dp(20), dp(8), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(CONTROL_HEIGHT_DP),
                1f);
        params.setMargins(dp(4), dp(4), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams weightTextParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(0, dp(4), dp(8), dp(4));
        return params;
    }

    private void addAction(GridLayout grid, String title, final Runnable action) {
        Button button = button(title, ButtonStyle.NORMAL);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(CONTROL_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(button, params);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(dp(4), dp(8), dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams rowSpinnerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(230),
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(0, dp(8), dp(8), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams statusParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(CONTROL_HEIGHT_DP),
                1f);
        params.setMargins(0, dp(8), dp(8), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams rowButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(72),
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(0, dp(8), 0, dp(4));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface ClientTask {
        String run(SkbClient skb) throws Exception;
    }

    private enum ButtonStyle {
        NORMAL,
        PRIMARY,
        DANGER
    }

    private abstract class AckAction implements Runnable {
        private final String name;

        AckAction(String name) {
            this.name = name;
        }

        @Override
        public final void run() {
            withClient(name, new ClientTask() {
                @Override
                public String run(SkbClient skb) throws Exception {
                    return formatAck(execute(skb));
                }
            });
        }

        abstract SkbClient.Ack execute(SkbClient skb) throws Exception;
    }

    private final class LoggingSerialTransport implements SerialTransport {
        private final SerialTransport delegate;
        private final byte[] rxLogBuffer = new byte[MAX_LOG_FRAME_LENGTH * 2];
        private int rxLogOffset = 0;
        private int rxLogExpectedLength = -1;

        LoggingSerialTransport(SerialTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(byte[] data) throws IOException {
            appendLogFromAnyThread("TX 原始: " + toHex(data));
            delegate.write(data);
        }

        @Override
        public byte[] read(int length, int timeoutMillis) throws IOException {
            byte[] data;
            try {
                data = delegate.read(length, timeoutMillis);
            } catch (IOException e) {
                rxLogOffset = 0;
                rxLogExpectedLength = -1;
                throw e;
            }
            appendRxLog(data);
            return data;
        }

        private void appendRxLog(byte[] data) {
            if (data.length == 0) {
                return;
            }
            if (rxLogOffset + data.length > rxLogBuffer.length) {
                appendLogFromAnyThread("RX 原始: " + toHex(Arrays.copyOf(rxLogBuffer, rxLogOffset)));
                rxLogOffset = 0;
                rxLogExpectedLength = -1;
            }

            System.arraycopy(data, 0, rxLogBuffer, rxLogOffset, data.length);
            rxLogOffset += data.length;
            if (rxLogExpectedLength < 0 && rxLogOffset >= 4) {
                rxLogExpectedLength = 5 + (rxLogBuffer[3] & 0xff);
            }
            if (rxLogExpectedLength > 0 && rxLogOffset >= rxLogExpectedLength) {
                appendLogFromAnyThread("RX 原始: " + toHex(Arrays.copyOf(rxLogBuffer, rxLogExpectedLength)));
                int remaining = rxLogOffset - rxLogExpectedLength;
                if (remaining > 0) {
                    System.arraycopy(rxLogBuffer, rxLogExpectedLength, rxLogBuffer, 0, remaining);
                }
                rxLogOffset = remaining;
                rxLogExpectedLength = rxLogOffset >= 4 ? 5 + (rxLogBuffer[3] & 0xff) : -1;
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
