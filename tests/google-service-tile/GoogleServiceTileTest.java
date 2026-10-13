package dev.rikumi.flymemod;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.widget.Toast;

public final class GoogleServiceTileTest {
    private static int checks, errors;
    private static SignalHooks.Hook hook;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void install() throws Exception {
        new GoogleServiceTileHooks((message, error) -> errors++).install(GoogleServiceTileTest.class.getClassLoader(),
                (owner, name, callback, args) -> hook = callback);
    }
    private static void create(Context context) {
        hook.apply(new SignalHooks.Chain() {
            public Object proceed() { return null; }
            public Object getThisObject() { return context; }
        });
    }
    private static void click(GoogleServiceTileService service) { service.onClick(); service.deliver(); }

    public static void main(String[] args) throws Exception {
        GoogleServiceTileService service = new GoogleServiceTileService();
        service.resolver.value = 1;
        install();
        Application.process = "com.android.systemui:screenshot";
        create(service);
        check(Context.backend == null);
        Application.process = GoogleServiceTile.SYSTEM_UI;
        create(service);
        Object receiver = Context.backend;
        create(service);
        check(Context.backend == receiver);
        check(GoogleServiceTile.PERMISSION.equals(Context.permission));
        service.onStartListening();
        check(service.tile.state == Tile.STATE_UNAVAILABLE);
        service.deliver();
        check(service.tile.state == Tile.STATE_ACTIVE);
        check(service.resolver.writes == 0); // Adding/listening never changes the native switch.
        check(service.collapses == 0 && Toast.shown == 0);
        service.resolver.external(0);
        check(service.tile.state == Tile.STATE_INACTIVE);
        service.resolver.external(1);
        check(service.tile.state == Tile.STATE_ACTIVE);
        click(service);
        check(service.resolver.value == 0 && service.tile.state == Tile.STATE_INACTIVE);
        check(Toast.last.equals(service.getString(R.string.google_service_disabling)) && service.collapses == 1);
        click(service);
        check(service.resolver.value == 1 && service.tile.state == Tile.STATE_ACTIVE);
        check(Toast.last.equals(service.getString(R.string.google_service_enabling)) && service.collapses == 2);
        service.onClick();
        Object first = service.callback;
        int toasts = Toast.shown;
        service.onClick();
        check(service.callback == first);
        check(Toast.shown == toasts);
        service.deliver();
        check(service.resolver.writes == 3);
        service.onClick();
        service.resolver.external(1); // Read current state at execution, not a stale UI snapshot.
        service.deliver();
        check(service.resolver.value == 0);
        service.keyguard.locked = true;
        toasts = Toast.shown;
        service.onClick();
        check(service.callback == null && service.unlock != null);
        check(Toast.shown == toasts);
        int before = service.resolver.writes;
        service.keyguard.locked = false;
        service.unlock.run(); service.deliver();
        check(service.resolver.writes == before + 1 && service.resolver.value == 1);
        service.onClick(); service.keyguard.locked = true; service.deliver();
        check(service.resolver.value == 1 && service.resolver.writes == before + 1);
        service.keyguard.locked = false;
        service.onStopListening();
        check(service.resolver.observer == null);
        service.onStartListening(); service.deliver();
        check(service.tile.state == Tile.STATE_ACTIVE);
        service.pm.installed = false;
        click(service);
        check(service.resolver.value == 1 && service.tile.state == Tile.STATE_UNAVAILABLE);
        check(service.tile.subtitle.equals(service.getString(R.string.google_service_not_installed)));
        service.pm.installed = true;
        service.resolver.writeOk = false;
        click(service);
        check(service.resolver.value == 1 && service.tile.state == Tile.STATE_ACTIVE);
        service.resolver.writeOk = true; service.resolver.fail = true;
        click(service);
        check(errors == 1 && service.resolver.value == 1);
        service.resolver.fail = false;
        Context.backend.ordered = false;
        Context.backend.onReceive(service, new Intent(GoogleServiceTile.ACTION).putExtra(GoogleServiceTile.TOGGLE, true));
        check(service.resolver.value == 1);
        Context.backend.ordered = true;
        Context.backend.onReceive(service, new Intent("unrelated").putExtra(GoogleServiceTile.TOGGLE, true));
        check(service.resolver.value == 1);
        Context.backend = null;
        service.onStopListening(); service.onStartListening(); service.deliver();
        check(service.tile.state == Tile.STATE_UNAVAILABLE && service.resolver.value == 1);
        check(service.tile.subtitle.equals(service.getString(R.string.google_service_bridge_unavailable)));
        install(); create(service);
        service.onStopListening(); service.onStartListening(); service.deliver();
        check(service.tile.state == Tile.STATE_ACTIVE);
        service.onClick(); service.onStopListening(); service.deliver();
        check(service.resolver.value == 0 && service.resolver.observer == null);
        service.onStartListening(); service.deliver();
        check(service.tile.state == Tile.STATE_INACTIVE);
        // A failed write must still report its result after the requested collapse stops listening.
        service.onCollapse = service::onStopListening;
        service.resolver.writeOk = false;
        click(service);
        check(service.resolver.value == 0 && service.resolver.observer == null);
        check(Toast.last.equals(service.getString(R.string.google_service_write_failed)));
        service.resolver.writeOk = true;
        service.onStartListening(); service.deliver();
        click(service);
        check(service.resolver.value == 1 && service.resolver.observer == null);
        check(Toast.last.equals(service.getString(R.string.google_service_enabling)));
        service.onStartListening(); service.deliver();
        check(service.tile.state == Tile.STATE_ACTIVE);
        service.onDestroy();
        check(service.resolver.observer == null);
        System.out.println("PASS: " + checks + " Google service tile lifecycle and receiver checks");
    }
}
