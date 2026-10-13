package dev.rikumi.flymemod;

import android.graphics.Path;
import android.graphics.PathIterator;

public final class ContourMaskCacheTest {
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ContourMaskCache cache=new ContourMaskCache();
        Path path=new Path().add(0,1,2).add(1,1,2,3,4).add(2,1,2,3,4,5,6)
                .add(3,1,2,3,4,5,6,.7f).add(4,1,2,3,4,5,6,7,8).add(5);
        check(!cache.matches(path,100,200,6),"empty cache");
        cache.remember(path,100,200,6);
        for(int i=0;i<1000;i++)check(cache.matches(path,100,200,6),"stable identity");
        check(PathIterator.iterations==0,"stable generation must not iterate geometry");
        Path rebuilt=new Path(path);
        check(cache.matches(rebuilt,100,200,6),"identical rebuilt path must reuse bitmap");
        int iterations=PathIterator.iterations;
        for(int i=0;i<1000;i++)check(cache.matches(rebuilt,100,200,6),"rebuilt identity");
        check(iterations==PathIterator.iterations,"equal rebuilt path should establish fast path");
        for(int part=0;part<5;part++) {
            int count=new int[]{2,4,6,7,8}[part];
            for(int coordinate=0;coordinate<count;coordinate++) {
                Path changed=new Path(path); changed.change(part,coordinate,42f);
                check(!cache.matches(changed,100,200,6),"changed point or conic weight");
            }
        }
        rebuilt.change(0,0,42f);
        check(!cache.matches(rebuilt,100,200,6),"same Path identity with new generation");
        check(!cache.matches(path,101,200,6),"width change");
        check(!cache.matches(path,100,201,6),"height change");
        check(!cache.matches(path,100,200,7),"stroke change");
        Path fill=new Path(path); fill.fill=1;
        check(!cache.matches(fill,100,200,6),"fill type change");
        android.os.Build.VERSION.SDK_INT=33;
        check(!cache.matches(path,100,200,6),"unsupported iterator API fallback");
        System.out.println("PASS: zero stable-path iterations; rebuilt equality; point, weight, fill, size and stroke invalidation");
    }
}
