package dev.rikumi.flymemod;

import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Provider-side bounded, read-only root sampling; called exclusively from the UI's worker. */
final class ChargingInputReader {
    // Standard power_supply input ADC attributes use microvolts/microamps.
    // Never use battery, UCSI source/contract nodes, limits or negotiated maxima.
    // The selected supply must remain online across both ADC reads.
    static final String COMMAND = "root=/sys/class/power_supply; for p in \"$root/usb\" \"$root/wireless\" \"$root/ac\" \"$root/mains\" \"$root/charger\" \"$root\"/*; do name=${p##*/}; case \"$name\" in battery*|bms*|*ucsi*|*source*) continue;; esac; t=$(cat \"$p/type\" 2>/dev/null) || continue; case \"$t\" in USB*|Mains) u=1; w=0;; Wireless) u=0; w=1;; *) continue;; esac; a=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$a\" = 1 ] || continue; v=$(cat \"$p/voltage_now\" 2>/dev/null) || continue; i=$(cat \"$p/current_now\" 2>/dev/null) || continue; case \"$v:$i\" in *[!0-9:]*|:*|*:) continue;; esac; [ ${#v} -le 8 ] && [ ${#i} -le 8 ] || continue; [ \"$v\" -ge 3000000 ] && [ \"$v\" -le 30000000 ] && [ \"$i\" -gt 0 ] && [ \"$i\" -le 15000000 ] || continue; b=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$b\" = 1 ] || continue; printf '%s %s %s %s %s %s' \"$u\" \"$w\" \"$v\" \"$i\" \"$u\" \"$w\"; exit 0; done";
    private long lastRead = -10000;
    private String cached = "";
    private String status = "not_read";
    synchronized String status() { return status; }
    synchronized String read() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < 3000) return cached;
        lastRead = now; cached = "";
        java.lang.Process process = null;
        try {
            String bounded = "timeout 1 sh -c '" + COMMAND.replace("'", "'\\''") + "'";
            process = new ProcessBuilder("su", "-c", bounded).redirectErrorStream(true).start();
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) { status = "timeout"; return ""; }
            if (process.exitValue() != 0) { status = "reader_exit_" + process.exitValue(); return ""; }
            String raw = new String(process.getInputStream().readNBytes(256), StandardCharsets.US_ASCII);
            if (ChargingInfo.parseMicro(raw) != null) { cached = raw; status = "valid_input"; }
            else status = raw.isBlank() ? "offline" : "invalid_input";
        } catch (Exception ignored) {
            status = "reader_error_" + ignored.getClass().getSimpleName();
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { if (process != null) process.destroy(); }
        return cached;
    }
}
