package dev.rikumi.flymemod;

import java.util.*;

final class SystemIconPackIndex {
    private final Map<String,String> exact=new LinkedHashMap<>(), first=new LinkedHashMap<>();
    static String component(String value) {
        if(value==null) return null;
        value=value.trim();
        if(value.startsWith("ComponentInfo{") && value.endsWith("}")) value=value.substring(14,value.length()-1);
        int slash=value.indexOf('/');
        if(slash<0) return value.isEmpty()?null:value;
        String pkg=value.substring(0,slash), name=value.substring(slash+1);
        if(pkg.isEmpty() || name.isEmpty()) return null;
        if(name.startsWith(".")) name=pkg+name;
        else if(name.indexOf('.')<0) name=pkg+"."+name;
        return pkg+"/"+name;
    }
    void add(String declared,String drawable) {
        String key=component(declared);
        if(key==null || drawable==null || drawable.isBlank()) return;
        int slash=key.indexOf('/'); String pkg=slash<0?key:key.substring(0,slash);
        if(slash>=0) exact.putIfAbsent(key,drawable);
        first.putIfAbsent(pkg,drawable);
    }
    record App(String defaultIcon,Map<String,String> entrances,String fallback) {
        App { entrances=Collections.unmodifiableMap(new LinkedHashMap<>(entrances)); }
        String forComponent(String requested) {
            String key=component(requested);
            return key!=null && entrances.containsKey(key)?entrances.get(key):defaultIcon;
        }
    }
    App resolve(String pkg,List<String> entrances) {
        LinkedHashMap<String,String> resolved=new LinkedHashMap<>();
        String fallback=first.getOrDefault(pkg,""); String preferred="";
        for(String entrance:entrances) {
            String key=component(entrance);
            if(key==null || !key.startsWith(pkg+"/")) continue;
            String icon=exact.getOrDefault(key,"");
            if(preferred.isEmpty() && !icon.isEmpty()) preferred=icon;
            resolved.putIfAbsent(key,icon.isEmpty()?fallback:icon);
        }
        return new App(preferred.isEmpty()?fallback:preferred,resolved,fallback);
    }
    static final class Generation<T> {
        private long ticket;
        private T value;
        synchronized long invalidate() { value=null; return ++ticket; }
        synchronized boolean publish(long expected,T next) {
            if(expected!=ticket) return false;
            value=next; return true;
        }
        synchronized T get() { return value; }
        synchronized long ticket() { return ticket; }
    }
}
