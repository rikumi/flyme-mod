package dev.rikumi.flymemod;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.util.Xml;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.xmlpull.v1.XmlPullParser;

final class SystemIconPackStore {
    static final Uri URI=Uri.parse("content://dev.rikumi.flymemod.icons");
    private static SystemIconPackStore instance;
    static synchronized SystemIconPackStore get(Context context) {
        if(instance==null) instance=new SystemIconPackStore(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences settings, selection;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SystemIconPackIndex.Generation<Snapshot> generations=new SystemIconPackIndex.Generation<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(p,key)-> {
        if(ModuleSettings.SYSTEM_ICON_PACK.equals(key)) rebuild();
    };
    private record Snapshot(String id,File directory,Map<String,SystemIconPackIndex.App> apps,
                            Map<String,String> files,int density) {}
    private SystemIconPackStore(Context context) {
        this.context=context;
        settings=ModuleSettings.preferences(context);
        selection=context.createDeviceProtectedStorageContext().getSharedPreferences("system_icon_pack_selection",0);
        settings.registerOnSharedPreferenceChangeListener(changes);
        rebuild();
    }
    synchronized void select(String pack) {
        if(pack==null || !(pack.equals("SYSTEM_ICONS") || pack.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
            throw new IllegalArgumentException("Invalid icon pack");
        if(pack.equals(selection.getString("pack",""))) return;
        selection.edit().putString("pack",pack).commit();
        rebuild();
    }
    void rebuild() {
        boolean wasReady=generations.get()!=null;
        long ticket=generations.invalidate();
        boolean enabled=settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false);
        if(enabled || wasReady) context.getContentResolver().notifyChange(URI,null);
        if(!enabled) return;
        String pack=selection.getString("pack","");
        // Until Launcher has reported its actual selection, never guess a pack from installed themes.
        if(pack.isEmpty()) return;
        worker.execute(()->build(ticket,pack));
    }
    private void build(long ticket,String pack) {
        File directory=null;
        try {
            if(generations.ticket()!=ticket) return;
            PackageManager pm=context.getPackageManager();
            SystemIconPackIndex index=new SystemIconPackIndex();
            Resources resources=null;
            if(!pack.equals("SYSTEM_ICONS")) {
                // A fresh version's Resources and a fresh generation prevent APK update reuse.
                try {
                    resources=pm.getResourcesForApplication(pm.getApplicationInfo(pack,0));
                    parse(resources,pack,index);
                } catch(PackageManager.NameNotFoundException missing) { /* An uninstalled pack yields native misses. */ }
            }
            LinkedHashMap<String,List<String>> entrances=new LinkedHashMap<>();
            Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for(ResolveInfo resolved:pm.queryIntentActivities(query,0)) {
                ActivityInfo activity=resolved.activityInfo;
                if(activity==null) continue;
                String component=SystemIconPackIndex.component(activity.packageName+"/"+activity.name);
                // Keep alias names, not targetActivity: the alias is the entry Launcher actually resolves.
                entrances.computeIfAbsent(activity.packageName,k->new ArrayList<>()).add(component);
            }
            LinkedHashMap<String,SystemIconPackIndex.App> apps=new LinkedHashMap<>();
            LinkedHashSet<String> needed=new LinkedHashSet<>();
            // QUERY_ALL_PACKAGES is explicitly declared; includes system/non-launchable/disabled packages.
            for(ApplicationInfo app:pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
                if(generations.ticket()!=ticket) return;
                ArrayList<String> entry=new ArrayList<>(entrances.getOrDefault(app.packageName,List.of()));
                Intent launch=pm.getLaunchIntentForPackage(app.packageName);
                String preferred=launch==null || launch.getComponent()==null?null:launch.getComponent().flattenToString();
                preferred=SystemIconPackIndex.component(preferred);
                if(preferred!=null && entry.remove(preferred)) entry.add(0,preferred);
                SystemIconPackIndex.App mapped=index.resolve(app.packageName,entry);
                apps.put(app.packageName,mapped);
                if(!mapped.defaultIcon().isEmpty()) needed.add(mapped.defaultIcon());
                for(String icon:mapped.entrances().values()) if(!icon.isEmpty()) needed.add(icon);
            }
            String id=UUID.randomUUID().toString();
            File parent=new File(context.getCacheDir(),"system-icon-pack");
            if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create icon cache");
            directory=new File(parent,id);
            if(!directory.mkdir()) throw new IOException("Cannot create icon generation");
            int density=Math.max(160,context.getResources().getDisplayMetrics().densityDpi)*2;
            int size=Math.max(48,Math.round(48*density/160f));
            LinkedHashMap<String,String> files=new LinkedHashMap<>();
            for(String name:needed) {
                if(generations.ticket()!=ticket) { delete(directory); return; }
                int resource=resources.getIdentifier(name,"drawable",pack);
                if(resource==0) resource=resources.getIdentifier(name,"mipmap",pack);
                if(resource==0) continue;
                try {
                    Drawable drawable=resources.getDrawableForDensity(resource,density,null);
                    Bitmap pixels;
                    try(IconContourDrawScope ignored=IconContourDrawScope.enter()) {
                        pixels=ColorOsIconPackCatalog.render(drawable,size);
                    }
                    String file=files.size()+".png";
                    try(OutputStream output=new FileOutputStream(new File(directory,file))) {
                        if(!pixels.compress(Bitmap.CompressFormat.PNG,100,output)) throw new IOException("Icon compression failed");
                    } finally { pixels.recycle(); }
                    files.put(name,file);
                } catch(RuntimeException error) {
                    // A malformed declared icon is a native fallback, never a synthetic mask.
                    Log.w("FlymeMod","Cannot cache declared icon "+pack+":"+name,error);
                }
            }
            Snapshot next=new Snapshot(id,directory,Collections.unmodifiableMap(apps),
                    Collections.unmodifiableMap(files),density);
            if(!generations.publish(ticket,next)) { delete(directory); return; }
            context.getContentResolver().notifyChange(URI,null);
            for(File old:Objects.requireNonNullElse(parent.listFiles(),new File[0])) if(!old.equals(directory)) delete(old);
        } catch(Exception error) {
            if(directory!=null) delete(directory);
            Log.e("FlymeMod","Cannot build system application icon pack cache",error);
        }
    }
    private static void parse(Resources resources,String pack,SystemIconPackIndex index)throws Exception {
        int xml=resources.getIdentifier("appfilter","xml",pack);
        if(xml!=0) {
            try(XmlResourceParser parser=resources.getXml(xml)) { parse(parser,resources,pack,index); }
        } else {
            try(InputStream input=resources.getAssets().open("appfilter.xml")) {
                XmlPullParser parser=Xml.newPullParser(); parser.setInput(input,"UTF-8"); parse(parser,resources,pack,index);
            } catch(FileNotFoundException missing) { /* No declarations means all packages miss. */ }
        }
    }
    private static void parse(XmlPullParser parser,Resources resources,String pack,SystemIconPackIndex index)throws Exception {
        for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next())
            if(event==XmlPullParser.START_TAG && "item".equalsIgnoreCase(parser.getName())) {
                String drawable=parser.getAttributeValue(null,"drawable");
                if(drawable!=null) drawable=ColorOsIconPackCatalog.resourceName(drawable);
                if(drawable!=null && (resources.getIdentifier(drawable,"drawable",pack)!=0
                        || resources.getIdentifier(drawable,"mipmap",pack)!=0))
                    index.add(parser.getAttributeValue(null,"component"),drawable);
            }
        // iconback/iconmask/iconupon do not enter the index.
    }
    Bundle lookup(String pkg,String component,int user) {
        Bundle result=new Bundle();
        if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false) || user!=android.os.Process.myUid()/100000) return result;
        synchronized(generations) {
            Snapshot snapshot=generations.get();
            if(snapshot==null) return result;
            SystemIconPackIndex.App app=snapshot.apps.get(pkg);
            String name=app==null?"":app.forComponent(component);
            String file=snapshot.files.get(name);
            if(file==null && app!=null) file=snapshot.files.get(app.fallback());
            result.putString("generation",snapshot.id);
            if(file==null) return result;
            result.putString("file",snapshot.id+"/"+file);
            result.putInt("density",snapshot.density);
            return result;
        }
    }
    ParcelFileDescriptor open(Uri uri)throws FileNotFoundException {
        synchronized(generations) {
            Snapshot snapshot=generations.get(); List<String> parts=uri.getPathSegments();
            if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false) || snapshot==null || parts.size()!=2
                    || !snapshot.id.equals(parts.get(0)) || !snapshot.files.containsValue(parts.get(1)))
                throw new FileNotFoundException("Icon cache generation unavailable");
            return ParcelFileDescriptor.open(new File(snapshot.directory,parts.get(1)),ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
    private static void delete(File file) {
        File[] children=file.listFiles(); if(children!=null) for(File child:children) delete(child);
        file.delete();
    }
}
