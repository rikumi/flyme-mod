package dev.rikumi.installer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public final class InstallActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button retry, manager;
    private File apk;
    private boolean permissionPending, binding;
    private volatile boolean installing;
    private boolean completed;
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("dev.rikumi.installer", InstallerService.class.getName()))
            .daemon(true).tag("install-" + java.util.UUID.randomUUID()).processNameSuffix("installer").version(1);
    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::authorize);
    private final Shizuku.OnBinderDeadListener died = () -> runOnUiThread(() -> {
        binding = false;
        permissionPending = false;
        if (!completed) show("Shizuku 已停止，请重新启动后重试。", true);
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code != PERMISSION_REQUEST) return;
        runOnUiThread(() -> {
            permissionPending = false;
            if (result == PackageManager.PERMISSION_GRANTED) authorize();
            else show("未获得 Shizuku 权限。请在 Shizuku 中允许“安装器”使用权限后重试。", true);
        });
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (isDestroyed() || completed) { releaseService(true); return; }
            if (installing) return;
            installing = true;
            show("正在安装，请稍候……", false);
            worker.execute(() -> {
                String result;
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    result = IInstallerService.Stub.asInterface(binder).install(fd, apk.length());
                } catch (Exception error) {
                    result = "安装失败：" + error.getMessage();
                } finally {
                    releaseService(true);
                }
                boolean success = "安装完成。".equals(result);
                installing = false;
                if (success || isDestroyed()) deleteApk();
                String message = result;
                runOnUiThread(() -> {
                    completed = success;
                    if (!isDestroyed()) show(message, !success);
                });
                if (isDestroyed()) worker.shutdown();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            if (!installing && !completed && !isDestroyed()) show("安装服务已断开。请检查 Shizuku 状态后重试。", true);
        }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setTextSize(18);
        status.setTextIsSelectable(true);
        layout.addView(status);
        retry = new Button(this);
        retry.setText("重试安装");
        retry.setOnClickListener(v -> authorize());
        layout.addView(retry);
        manager = new Button(this);
        manager.setText("打开 Shizuku");
        manager.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
            else show("请先安装并启动 Shizuku。", true);
        });
        layout.addView(manager);
        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> finish());
        layout.addView(close);
        setContentView(layout);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        // Do not repeat an installation if Android recreates a previously active task.
        if (savedState != null && savedState.getBoolean("submitted")) {
            completed = true;
            show("此前的安装已提交。请重新打开 APK 查看或重试安装。", false);
            return;
        }
        Uri uri = Intent.ACTION_SEND.equals(getIntent().getAction())
                ? getIntent().getParcelableExtra(Intent.EXTRA_STREAM) : getIntent().getData();
        if (uri == null && getIntent().getClipData() != null && getIntent().getClipData().getItemCount() > 0)
            uri = getIntent().getClipData().getItemAt(0).getUri();
        if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) {
            completed = true;
            show("请从文件管理器打开 APK，或将 APK 分享到“安装器”。", false);
            return;
        }
        show("正在读取 APK……", false);
        Uri source = uri;
        worker.execute(() -> prepare(source));
    }

    private void prepare(Uri uri) {
        File temporary = null;
        try {
            temporary = File.createTempFile("install-", ".apk", getCacheDir());
            try (InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = Files.newOutputStream(temporary.toPath())) {
                if (input == null) throw new IllegalArgumentException("无法读取 APK");
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
            }
            PackageInfo info = getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), 0);
            if (info == null || temporary.length() == 0) throw new IllegalArgumentException("不是有效的 APK 文件");
            File ready = temporary;
            runOnUiThread(() -> {
                if (isDestroyed()) { ready.delete(); return; }
                apk = ready;
                authorize();
            });
        } catch (Exception error) {
            if (temporary != null) temporary.delete();
            runOnUiThread(() -> {
                completed = true;
                if (!isDestroyed()) show("无法读取 APK：" + error.getMessage(), false);
            });
        }
    }

    private void authorize() {
        if (isDestroyed() || completed || apk == null || installing || binding || permissionPending) return;
        if (!Shizuku.pingBinder()) { show("请先启动 Shizuku，然后重试。", true); return; }
        try {
            if (Shizuku.isPreV11()) { show("请更新 Shizuku 后重试。", true); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    show("请在 Shizuku 的授权应用中允许“安装器”，然后重试。", true);
                    return;
                }
                permissionPending = true;
                show("请在 Shizuku 授权窗口中允许安装器使用权限。", false);
                Shizuku.requestPermission(PERMISSION_REQUEST);
                return;
            }
            binding = true;
            show("正在连接安装服务……", false);
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException error) {
            binding = false;
            permissionPending = false;
            show("无法连接 Shizuku：" + error.getMessage(), true);
        }
    }

    private void show(String message, boolean canRetry) {
        status.setText(message);
        retry.setVisibility(canRetry ? android.view.View.VISIBLE : android.view.View.GONE);
        retry.setEnabled(!installing);
        manager.setVisibility(canRetry ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private void releaseService(boolean remove) {
        try { Shizuku.unbindUserService(serviceArgs, connection, remove); }
        catch (RuntimeException ignored) {}
        binding = false;
    }
    private void deleteApk() { if (apk != null) apk.delete(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("submitted", installing || completed);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        if (binding) releaseService(!installing);
        if (!installing) { deleteApk(); worker.shutdown(); }
        super.onDestroy();
    }
}
