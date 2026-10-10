package dev.rikumi.installer;

import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** This Binder is instantiated by Shizuku with shell/root identity, not as an Android Service. */
public final class InstallerService extends IInstallerService.Stub {
    public InstallerService() {}

    @Override public synchronized String install(ParcelFileDescriptor apk, long size) {
        if (apk == null || size <= 0) return "安装失败：APK 为空。";
        int user = Binder.getCallingUid() / 100000;
        Process process = null;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(apk)) {
            process = new ProcessBuilder("/system/bin/pm", "install", "-r", "--user",
                    Integer.toString(user), "-S", Long.toString(size), "-")
                    .redirectErrorStream(true).start();
            Process running = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    for (int n; (n = stream.read(buffer)) != -1;) {
                        // Keep draining even if the diagnostic buffer reaches its limit.
                        int remaining = 65536 - output.size();
                        if (remaining > 0) output.write(buffer, 0, Math.min(n, remaining));
                    }
                } catch (Exception ignored) {}
            }, "installer-result");
            reader.start();
            Exception writeError = null;
            try (OutputStream sink = process.getOutputStream()) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) sink.write(buffer, 0, n);
            } catch (Exception error) {
                // pm can reject an APK before consuming stdin. Preserve its actual
                // INSTALL_FAILED_* diagnostic instead of replacing it with EPIPE.
                writeError = error;
            }
            int result = process.waitFor();
            reader.join();
            String message = output.toString(StandardCharsets.UTF_8.name()).trim();
            if (message.isEmpty() && writeError != null) message = writeError.getMessage();
            return formatResult(result, message);
        } catch (Exception error) {
            if (process != null) process.destroy();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return "安装失败：" + error.getMessage();
        }
    }

    static String formatResult(int exitCode, String output) {
        String message = output == null ? "" : output.trim();
        return exitCode == 0 && java.util.Arrays.asList(message.split("\\r?\\n")).contains("Success")
                ? "安装完成。" : "安装失败：\n" + (message.isEmpty() ? "退出码 " + exitCode : message);
    }

    @Override public void destroy() { System.exit(0); }
}
