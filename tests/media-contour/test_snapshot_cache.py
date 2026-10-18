"""Exercise production snapshot caching, ownership, invalidation and RT immutability."""
from pathlib import Path
import subprocess,tempfile
scope={};exec(Path('tests/statusbar-overlay/test_lifecycle.py').read_text().split('with tempfile.TemporaryDirectory()')[0],scope)
files={'dev/rikumi/flymemod/SignalHooks.java':scope['files']['dev/rikumi/flymemod/SignalHooks.java']}
files['com/android/internal/graphics/drawable/FlymeEffectDrawable.java']='''package com.android.internal.graphics.drawable;
import java.util.*;import dev.rikumi.flymemod.SignalHooks;
public class FlymeEffectDrawable {
public long mFlymeEffectId;public int uniform;public int[] position={0};
public FlymeEffectDrawable(long id){mFlymeEffectId=id;}
public static class FlymeEffectRegion {public static int allocations;public final int uniform;public final int[] position;FlymeEffectRegion(FlymeEffectDrawable d){allocations++;uniform=d.uniform;position=d.position;}}
public static class Aggregator {
public boolean mHasUiUpdates=true;public Set<FlymeEffectDrawable> mDrawables=new LinkedHashSet<>();public FlymeEffectRegion[] mTmpFlymeEffectRegionsForFrame;
public void onFlymeEffectDrawableUpdated(FlymeEffectDrawable d){SignalHooks.dispatch(this,getClass().getName(),"onFlymeEffectDrawableUpdated",new Object[]{d},a->{mHasUiUpdates=true;return null;});}
public FlymeEffectRegion[] getFlymeEffectRegionsCopyForRT(){return (FlymeEffectRegion[])SignalHooks.dispatch(this,getClass().getName(),"getFlymeEffectRegionsCopyForRT",new Object[]{},a->{if(mHasUiUpdates){mTmpFlymeEffectRegionsForFrame=mDrawables.stream().map(FlymeEffectRegion::new).toArray(FlymeEffectRegion[]::new);mHasUiUpdates=false;}return mTmpFlymeEffectRegionsForFrame;});}
}}
'''
files['dev/rikumi/flymemod/SnapshotTest.java']='''package dev.rikumi.flymemod;
import java.util.*;import com.android.internal.graphics.drawable.FlymeEffectDrawable;import com.android.internal.graphics.drawable.FlymeEffectDrawable.*;
public class SnapshotTest {
static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
public static void main(String[] args)throws Exception{
new BackdropSnapshotCache().install(SnapshotTest.class.getClassLoader(),Set.of(1L),SignalHooks.INSTALLER,m->{},(m,e)->{throw new AssertionError(m,e);});
Aggregator a=new Aggregator();for(int i=0;i<20;i++)a.mDrawables.add(new FlymeEffectDrawable(1));FlymeEffectDrawable nativeEffect=new FlymeEffectDrawable(2);a.mDrawables.add(nativeEffect);
FlymeEffectDrawable first=a.mDrawables.iterator().next();FlymeEffectRegion[] before=a.getFlymeEffectRegionsCopyForRT();check(FlymeEffectRegion.allocations==21);check(before==a.getFlymeEffectRegionsCopyForRT());
for(int i=1;i<=100;i++){first.uniform=i;a.onFlymeEffectDrawableUpdated(first);FlymeEffectRegion[] after=a.getFlymeEffectRegionsCopyForRT();check(after[0].uniform==i);check(after[1]==before[1]);check(after[20]!=before[20]);check(before[0].uniform==0);}
check(FlymeEffectRegion.allocations==221);
first.position[0]=123;check(a.getFlymeEffectRegionsCopyForRT()[0].position[0]==123);check(FlymeEffectRegion.allocations==221);
a.mDrawables.remove(first);a.onFlymeEffectDrawableUpdated(first);check(a.getFlymeEffectRegionsCopyForRT().length==20);
first.uniform=777;a.mDrawables.add(first);a.onFlymeEffectDrawableUpdated(first);FlymeEffectRegion[] restored=a.getFlymeEffectRegionsCopyForRT();check(restored[20].uniform==777);
Aggregator other=new Aggregator();other.mDrawables.add(nativeEffect);check(other.getFlymeEffectRegionsCopyForRT().length==1);nativeEffect.uniform=9;other.onFlymeEffectDrawableUpdated(nativeEffect);check(other.getFlymeEffectRegionsCopyForRT()[0].uniform==9);
System.out.println("PASS: "+checks+" production snapshot ownership, invalidation and immutability checks; 221 snapshots vs 2121 native in fixture");}}
'''
files['dev/rikumi/flymemod/BackdropSnapshotCache.java']=Path('app/src/main/java/dev/rikumi/flymemod/BackdropSnapshotCache.java').read_text()
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 root=Path(tmp)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac','-d',tmp,*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'dev.rikumi.flymemod.SnapshotTest'],check=True)
