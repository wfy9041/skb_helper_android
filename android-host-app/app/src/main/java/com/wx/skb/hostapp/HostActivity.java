package com.wx.skb.hostapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** 宿主应用测试页，用于验证公开 SDK API 与原生调试面板。 */
public final class HostActivity extends Activity {
    private static final String SERIAL_PORT = "/dev/ttyS5";
    private EditText lockIdInput;
    private TextView resultView;
    private Button connectionButton;
    private Button callSdkButton;
    private Button keepOpenButton;
    private Button keepCloseButton;
    private Button delayCloseButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("SKB SDK 测试");
        setContentView(buildContentView());
        refreshConnectionState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnectionState();
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("SKB SDK 测试页");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 30, 45));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView hint = new TextView(this);
        hint.setText("串口固定为 /dev/ttyS5；打开串口后即可调用 SDK 接口。");
        hint.setTextSize(15);
        hint.setTextColor(Color.rgb(89, 101, 121));
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint, wrapMatch());

        TextView lockLabel = new TextView(this);
        lockLabel.setText("门号");
        lockLabel.setTextSize(16);
        lockLabel.setTextColor(Color.rgb(20, 32, 51));
        root.addView(lockLabel, wrapMatch());

        lockIdInput = new EditText(this);
        lockIdInput.setText("1");
        lockIdInput.setSingleLine(true);
        lockIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        lockIdInput.setHint("请输入门号");
        root.addView(lockIdInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        connectionButton = button("打开串口");
        connectionButton.setOnClickListener(view -> toggleSerialConnection());
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        connectionParams.setMargins(0, dp(20), 0, dp(12));
        root.addView(connectionButton, connectionParams);

        callSdkButton = button("调用 SDK 接口");
        callSdkButton.setOnClickListener(view -> callSdkOpenLock());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        actionParams.setMargins(0, 0, 0, dp(12));
        root.addView(callSdkButton, actionParams);

        LinearLayout extraActions = new LinearLayout(this);
        extraActions.setOrientation(LinearLayout.VERTICAL);
        keepOpenButton = button("SDK 常开");
        keepOpenButton.setOnClickListener(view -> callSdkDoorAction("常开"));
        extraActions.addView(keepOpenButton, actionParams());
        keepCloseButton = button("SDK 常关");
        keepCloseButton.setOnClickListener(view -> callSdkDoorAction("常关"));
        extraActions.addView(keepCloseButton, actionParams());
        delayCloseButton = button("SDK 延时关（1秒）");
        delayCloseButton.setOnClickListener(view -> callSdkDoorAction("延时关"));
        extraActions.addView(delayCloseButton, actionParams());
        root.addView(extraActions, wrapMatch());

        Button debugButton = button("打开原生 SDK 调试面板");
        debugButton.setOnClickListener(view -> SkbSdk.openDebug(this));
        root.addView(debugButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        resultView = new TextView(this);
        resultView.setText("状态：等待操作");
        resultView.setTextSize(15);
        resultView.setTextColor(Color.rgb(70, 80, 95));
        resultView.setPadding(0, dp(20), 0, 0);
        root.addView(resultView, wrapMatch());
        return root;
    }

    private void toggleSerialConnection() {
        if (SkbSdk.getState() == SkbSdk.State.CONNECTED) {
            connectionButton.setEnabled(false);
            resultView.setText("状态：正在关闭 " + SERIAL_PORT + "...");
            SkbSdk.shutdown(new SkbSdk.Callback<Void>() {
                @Override
                public void onSuccess(Void value) {
                    refreshConnectionState();
                }

                @Override
                public void onError(Exception error) {
                    showConnectionError(error);
                }
            });
            return;
        }

        connectionButton.setEnabled(false);
        resultView.setText("状态：正在打开 " + SERIAL_PORT + "...");
        SkbSdk.init(this, SERIAL_PORT, new SkbSdk.Callback<Void>() {
            @Override
            public void onSuccess(Void value) {
                refreshConnectionState();
            }

            @Override
            public void onError(Exception error) {
                showConnectionError(error);
            }
        });
    }

    private void refreshConnectionState() {
        if (connectionButton == null || resultView == null) {
            return;
        }
        SkbSdk.State state = SkbSdk.getState();
        boolean connected = state == SkbSdk.State.CONNECTED;
        connectionButton.setEnabled(state != SkbSdk.State.CONNECTING);
        connectionButton.setText(connected ? "关闭串口" : "打开串口");
        callSdkButton.setEnabled(connected);
        keepOpenButton.setEnabled(connected);
        keepCloseButton.setEnabled(connected);
        delayCloseButton.setEnabled(connected);
        if (connected) {
            resultView.setText("状态：已连接 " + SERIAL_PORT);
        } else if (state == SkbSdk.State.RECONNECTING) {
            resultView.setText("状态：等待重连 " + SERIAL_PORT);
        } else if (state == SkbSdk.State.CONNECTING) {
            resultView.setText("状态：正在连接 " + SERIAL_PORT + "...");
        } else {
            resultView.setText("状态：串口未连接");
        }
    }

    private void showConnectionError(Exception error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        refreshConnectionState();
        resultView.setText("状态：串口操作失败\n" + message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void callSdkOpenLock() {
        callSdkDoorAction("瞬时开锁");
    }

    private void callSdkDoorAction(String action) {
        if (SkbSdk.getState() != SkbSdk.State.CONNECTED) {
            resultView.setText("状态：请先打开串口 " + SERIAL_PORT);
            return;
        }
        String value = lockIdInput.getText().toString().trim();
        if (value.isEmpty()) {
            lockIdInput.setError("请输入门号");
            return;
        }
        final int lockId;
        try {
            lockId = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            lockIdInput.setError("门号必须是数字");
            return;
        }
        if (lockId <= 0) {
            lockIdInput.setError("门号必须大于 0");
            return;
        }

        resultView.setText("状态：正在调用 SDK " + action + "...");
        SkbSdk.Callback<SkbSdk.DoorResult> callback = new SkbSdk.Callback<SkbSdk.DoorResult>() {
            @Override
            public void onSuccess(SkbSdk.DoorResult value) {
                logDoorResult(action, lockId, value, null);
                resultView.setText("状态：" + action + "完成，"
                        + (value.isOpen() ? "门已打开" : "门已关闭"));
            }

            @Override
            public void onError(Exception error) {
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                logDoorResult(action, lockId, null, error);
                resultView.setText("状态：调用失败\n" + message);
                Toast.makeText(HostActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        };
        if ("瞬时开锁".equals(action)) {
            SkbSdk.openLock(lockId, callback);
        } else if ("常开".equals(action)) {
            SkbSdk.keepOpen(lockId, callback);
        } else if ("常关".equals(action)) {
            SkbSdk.keepClose(lockId, callback);
        } else {
            SkbSdk.delayClose(lockId, 20, callback);
        }
    }

    private void logDoorResult(String operation, int lockId,
                               SkbSdk.DoorResult result, Exception error) {
        try {
            JSONObject json = new JSONObject();
            json.put("operation", operation);
            json.put("lockId", lockId);
            if (error == null) {
                json.put("status", "SUCCESS");
                json.put("resultLockId", result.getLockId());
                json.put("doorStatus", result.getStatus());
                json.put("isOpen", result.isOpen());
                json.put("requestFrame", toHex(result.getRequestFrame()));
                json.put("responseFrame", toHex(result.getResponseFrame()));
            } else {
                json.put("status", "ERROR");
                json.put("errorType", error.getClass().getSimpleName());
                json.put("message", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            Log.d("SKB_API_RESULT", json.toString());
        } catch (Exception serializeError) {
            Log.e("SKB_API_RESULT", "SDK 返回结果序列化失败", serializeError);
        }
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format(java.util.Locale.US, "%02X", data[i] & 0xff));
        }
        return builder.toString();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        return button;
    }

    private LinearLayout.LayoutParams wrapMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
