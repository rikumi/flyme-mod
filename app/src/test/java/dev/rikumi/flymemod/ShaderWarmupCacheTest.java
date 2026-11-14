package dev.rikumi.flymemod;

public final class ShaderWarmupCacheTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    public static void main(String[] args) {
        ShaderWarmupCache<String> cache = new ShaderWarmupCache<>();
        ShaderWarmupGate host = new ShaderWarmupGate();
        cache.updateSettings("saturation=50");
        long settings = cache.generation();
        check(cache.find(24) == null);
        long ticket = host.begin();
        check(!cache.publish(24, "registered only", host, settings));
        check(cache.find(1) == null);
        check(host.presented(ticket));
        check(cache.publish(24, "status bar GPU program", host, settings));
        // Hidden shade and notification roots can reuse the program without compiling on first draw.
        check("status bar GPU program".equals(cache.find(1)));
        check("status bar GPU program".equals(cache.find(24)));
        check(cache.find(25) == null);
        host.invalidate();
        check("status bar GPU program".equals(cache.find(24))); // Host detach doesn't uncompile a program.
        long bigger = host.begin();
        check(!cache.publish(48, "not presented", host, settings));
        check(cache.find(25) == null);
        check(host.presented(bigger));
        check(cache.publish(48, "larger GPU program", host, settings));
        check("larger GPU program".equals(cache.find(25)));
        check("status bar GPU program".equals(cache.find(24)));
        check(!cache.updateSettings("saturation=50"));
        check(cache.generation() == settings);
        check("status bar GPU program".equals(cache.find(24)));
        check(cache.updateSettings("saturation=100"));
        check(cache.find(1) == null);
        check(!cache.publish(24, "stale settings", host, settings));
        check(cache.publish(24, "new settings", host, cache.generation()));
        check("new settings".equals(cache.find(24)));
        System.out.println("PASS: " + checks + " shared shader warmup cache checks");
    }
}
