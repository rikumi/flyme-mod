"""Exercise the production compaction hook with reduced Flyme layout/session fixtures."""
from pathlib import Path
import importlib.util, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('back_hooks', ROOT/'tests/back-gesture/test_hooks.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
sources = {k:m.SOURCES[k] for k in ['dev/rikumi/flymemod/SignalHooks.java', 'dev/rikumi/flymemod/HookRuntime.java']}
sources.update({
'android/content/Context.java':'package android.content; public class Context {}',
'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os; public class Handler {public Handler(Looper l){} public void postDelayed(Runnable r,long t){}}',
'android/media/session/PlaybackState.java':'package android.media.session; public class PlaybackState {public static final int STATE_PLAYING=3; public int state; public PlaybackState(int s){state=s;} public int getState(){return state;}}',
'android/media/session/MediaController.java':'package android.media.session; public class MediaController {public PlaybackState state; public PlaybackState getPlaybackState(){return state;}}',
'android/view/View.java':'''package android.view; public class View {
public static final int GONE=8; public Object parent; public ViewGroup.MarginLayoutParams lp; public int visibility,requests;
public Object getParent(){return parent;} public Object getLayoutParams(){return lp;} public int getVisibility(){return visibility;}
public void setVisibility(int v){visibility=v;} public void requestLayout(){requests++;} public void post(Runnable r){r.run();}
public boolean isAttachedToWindow(){return true;} public boolean isShown(){return true;}
public android.content.Context getContext(){return new android.content.Context();} public void measure(int w,int h){}
public static class MeasureSpec {public static int EXACTLY=1;public static int makeMeasureSpec(int n,int m){return n;}}
}''',
'android/view/ViewGroup.java':'''package android.view; import java.util.*; public class ViewGroup extends View {
public List<View> children=new ArrayList<>(); public void add(View v){children.add(v);v.parent=this;}
public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);}
public static class MarginLayoutParams {public int leftMargin,rightMargin,topMargin,bottomMargin,width,height;}
}''',
'com/flyme/systemui/controlcenter/qs/CellLayoutLayoutParams.java':'''package com.flyme.systemui.controlcenter.qs;
public class CellLayoutLayoutParams extends android.view.ViewGroup.MarginLayoutParams {
public int mCellX,mCellY,cellHSpan=2,cellVSpan=2,pageIndex,mTmpCellX,mTmpCellY,x,y; public boolean useTmpCoords,isLockedToGrid=true;
}''',
'com/flyme/systemui/controlcenter/qs/UnifiedTileLayout.java':'''package com.flyme.systemui.controlcenter.qs;
public class UnifiedTileLayout extends android.view.ViewGroup {public int mCellWidth=100,mCellHeight=100,mCellMarginHorizontal=10,mCellMarginVertical=10;public boolean isTemp;
public void onMeasure(int w,int h){dev.rikumi.flymemod.HookRuntime.call(this,getClass(),"onMeasure",new Object[]{w,h},()->null);}}
''',
'com/flyme/systemui/controlcenter/qs/PagedUnifiedTileLayout.java':'package com.flyme.systemui.controlcenter.qs;public class PagedUnifiedTileLayout {public void setEditMode(boolean e){}}',
'com/flyme/systemui/controlcenter/phone/MzQSPanel.java':'package com.flyme.systemui.controlcenter.phone;public class MzQSPanel {}',
'com/flyme/systemui/qs/tileimpl/ConnectivityQSTileViewImpl.java':'package com.flyme.systemui.qs.tileimpl;public class ConnectivityQSTileViewImpl extends android.view.View {}',
'com/flyme/systemui/qs/media/QsMediaView.java':'package com.flyme.systemui.qs.media;public class QsMediaView extends android.view.View {}',
'com/flyme/systemui/qs/media/QsMediaController.java':'''package com.flyme.systemui.qs.media;public class QsMediaController {
public android.media.session.MediaController mMediaController=new android.media.session.MediaController();public QsMediaView view=new QsMediaView();
public QsMediaView getView(){return view;}public void onViewAttached(){}public Object getQsMediaData(boolean p){return null;}
public void refreshUI(String reason){dev.rikumi.flymemod.HookRuntime.call(this,getClass(),"refreshUI",new Object[]{reason},()->null);}
public static class Factory {public QsMediaController create(com.flyme.systemui.controlcenter.phone.MzQSPanel p){return null;}}}
''',
'dev/rikumi/flymemod/SplitNetworkCardHooks.java':'package dev.rikumi.flymemod;class SplitNetworkCardHooks {boolean horizontal;void setHorizontal(android.view.View v,boolean h){horizontal=h;}}',
'dev/rikumi/flymemod/Test.java':'''package dev.rikumi.flymemod;
import android.view.*;import android.media.session.*;import com.flyme.systemui.controlcenter.qs.*;import com.flyme.systemui.qs.media.*;import com.flyme.systemui.qs.tileimpl.*;
public class Test {static boolean enabled=true;static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
public static void main(String[] args)throws Exception {
SplitNetworkCardHooks split=new SplitNetworkCardHooks();FoldIdleMediaHooks h=new FoldIdleMediaHooks(Test.class.getClassLoader(),c->{},()->enabled,split,(s,t)->{if(t!=null)throw new AssertionError(t);});h.install(HookRuntime::install);
UnifiedTileLayout layout=new UnifiedTileLayout();ConnectivityQSTileViewImpl network=new ConnectivityQSTileViewImpl();network.lp=new CellLayoutLayoutParams();layout.add(network);
QsMediaController c=new QsMediaController();CellLayoutLayoutParams mp=new CellLayoutLayoutParams();mp.mCellX=2;c.view.lp=mp;layout.add(c.view);
for(int state:new int[]{0,1,2,6,7,8}) {c.mMediaController.state=new PlaybackState(state);c.refreshUI("state changed");layout.onMeasure(0,0);check(c.view.visibility==8&&split.horizontal);}
check(layout.requests==6);check(mp.mCellX==2&&mp.cellHSpan==2&&mp.cellVSpan==2);
c.mMediaController.state=new PlaybackState(3);c.refreshUI("playing");layout.onMeasure(0,0);check(c.view.visibility==0&&!split.horizontal);
c.mMediaController.state=null;c.refreshUI("no state");layout.onMeasure(0,0);check(c.view.visibility==8);
enabled=false;layout.onMeasure(0,0);check(c.view.visibility==0&&!split.horizontal);
enabled=true;c.mMediaController=null;c.refreshUI("removed");layout.onMeasure(0,0);check(c.view.visibility==8);
layout.isTemp=true;layout.onMeasure(0,0);check(c.view.visibility==0&&!split.horizontal);
layout.isTemp=false;mp.mCellY=3;layout.onMeasure(0,0);check(c.view.visibility==0&&!split.horizontal);
System.out.println("PASS: "+checks+" production idle media compaction checks");}}
''',
})
with tempfile.TemporaryDirectory() as folder:
 base=Path(folder); paths=[]
 for name,source in sources.items():
  f=base/name; f.parent.mkdir(parents=True,exist_ok=True); f.write_text(source); paths.append(str(f))
 paths.append(str(ROOT/'app/src/main/java/dev/rikumi/flymemod/FoldIdleMediaHooks.java'))
 subprocess.run([str(m.JAVA/'javac'),'-d',str(base/'classes'),*paths],check=True)
 subprocess.run([str(m.JAVA/'java'),'-cp',str(base/'classes'),'dev.rikumi.flymemod.Test'],check=True)
