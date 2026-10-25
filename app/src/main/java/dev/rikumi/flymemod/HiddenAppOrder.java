package dev.rikumi.flymemod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class HiddenAppOrder {
    static List<String> reconcile(Set<String> packages,String saved) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(saved!=null)for(String pkg:saved.split("\n"))if(packages.contains(pkg))result.add(pkg);
        TreeSet<String> added=new TreeSet<>(packages);added.removeAll(result);result.addAll(added);
        return new ArrayList<>(result);
    }

    static <T> List<T> move(List<T> items,int from,int to) {
        List<T> result=new ArrayList<>(items);
        if(from<0 ||from>=result.size() ||to<0 ||to>=result.size() ||from==to)return result;
        result.add(to,result.remove(from));return result;
    }

    static int hit(float x,float y,int width,int height,int page,int count) {
        if(width<=0 ||height<=0 ||x<0 ||x>=width ||y<0 ||y>=height)return -1;
        int index=page*12+(int)(y*4/height)*3+(int)(x*3/width);
        return index<count?index:-1;
    }

    static int edge(float x,float y,int width,int height,int page,int pages) {
        if(width<=0 ||height<=0 ||y<0 ||y>=height)return 0;
        float margin=width*.12f;
        if(x<margin &&page>0)return -1;
        if(x>width-margin &&page<pages-1)return 1;
        return 0;
    }

    static int slot(float x,float y,int width,int height,int page,int count) {
        if(width<=0 ||height<=0 ||count<=0 ||y<0 ||y>=height)return -1;
        int column=Math.max(0,Math.min(2,(int)Math.floor(x*3/width)));
        int row=Math.max(0,Math.min(3,(int)Math.floor(y*4/height)));
        return Math.min(count-1,page*12+row*3+column);
    }
}
