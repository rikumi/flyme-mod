package dev.rikumi.flymemod;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.FileNotFoundException;

/** Read-only declared icons for scoped callers; only Launcher can publish the selected pack. */
public final class SystemIconPackProvider extends ContentProvider {
    @Override public boolean onCreate() { SystemIconPackStore.get(getContext()); return true; }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        SystemIconPackStore store=SystemIconPackStore.get(getContext());
        if("selection".equals(method)) {
            String[] packages=getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            boolean launcher=packages!=null && java.util.Arrays.asList(packages).contains("com.meizu.flyme.launcher");
            if(!launcher) throw new SecurityException("Only Launcher publishes the selected icon pack");
            store.select(argument); return Bundle.EMPTY;
        }
        if("lookup".equals(method) && extras!=null && argument!=null)
            return store.lookup(argument,extras.getString("component"),extras.getInt("user",-1));
        return Bundle.EMPTY;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read-only icon cache");
        return SystemIconPackStore.get(getContext()).open(uri);
    }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
