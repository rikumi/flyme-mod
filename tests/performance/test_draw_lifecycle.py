#!/usr/bin/env python3
"""Run the production tint callback and listener bodies against counting lifecycle doubles."""
from pathlib import Path
import re
import subprocess
import tempfile

repo=Path(__file__).resolve().parents[2]
source=repo/'app/src/main/java/dev/rikumi/flymemod'
jdk=Path('/opt/homebrew/opt/openjdk@21/bin')

def block(text, marker):
    start=text.index('{',text.index(marker))
    depth=1
    end=start+1
    while depth:
        depth += (text[end]=='{') - (text[end]=='}')
        end+=1
    return text[start:end]

appearance=(source/'NotificationAppearanceHooks.java').read_text()
draw=block(appearance,'installer.hook("android.widget.ImageView", "onDraw"')
identify=block(appearance,'private boolean isDismissIcon(')
java='''import java.util.*;
public class DrawLifecycleTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class ColorFilter {}
 static class View {static final int NO_ID=-1; final Observer observer=new Observer(); Observer getViewTreeObserver(){return observer;}}
 static class ImageView extends View {
  int id=1,writes; boolean night; ColorFilter filter; Resources resources=new Resources();
  int getId(){return id;} Resources getResources(){return resources;}
  ColorFilter getColorFilter(){return filter;} void setColorFilter(ColorFilter next){filter=next;writes++;}
 }
 static class Resources {
  int lookups;
  String getResourceEntryName(int id){lookups++;return id==1?"dismiss_view":id==2?"filter_dismiss_view":"other";}
  static class NotFoundException extends RuntimeException {}
 }
 static class SparseBooleanArray {
  List<Integer> keys=new ArrayList<>();List<Boolean> values=new ArrayList<>();
  int indexOfKey(int id){return keys.indexOf(id);} boolean valueAt(int index){return values.get(index);}
  void put(int id,boolean value){keys.add(id);values.add(value);}
 }
 static class Chain {ImageView view;Chain(ImageView view){this.view=view;}Object getThisObject(){return view;}Object proceed(){return null;}}
 final ColorFilter dismissFilter=new ColorFilter();
 final Map<Resources,SparseBooleanArray> dismissIds=new WeakHashMap<>();
 final Map<ImageView,ColorFilter> dismissOriginals=new WeakHashMap<>();
 boolean night(ImageView view){return view.night;}
 Object draw(Chain chain) DRAW_BODY
 private boolean isDismissIcon(ImageView icon) IDENTIFY_BODY
 interface Listener {void onViewAttachedToWindow(View view);void onViewDetachedFromWindow(View view);}
 static class Observer {
  List<Object> listeners=new ArrayList<>();boolean isAlive(){return true;}
  void addOnPreDrawListener(Object listener){listeners.add(listener);}
  void removeOnPreDrawListener(Object listener){listeners.remove(listener);}
 }
 LISTENER_CLASSES
 public static void main(String[] args){
  DrawLifecycleTest test=new DrawLifecycleTest();ImageView icon=new ImageView();Chain chain=new Chain(icon);
  ColorFilter original=new ColorFilter();icon.filter=original;
  for(int i=0;i<1000;i++)test.draw(chain);
  check(icon.writes==1,"stable light icon must not invalidate every draw");
  check(icon.resources.lookups==1,"resource name must be resolved once");
  icon.night=true;test.draw(chain);
  check(icon.filter==original && icon.writes==2,"night mode must restore original filter");
  test.draw(chain);check(icon.writes==2,"stable night mode must not invalidate");
  icon.night=false;test.draw(chain);ColorFilter replacement=new ColorFilter();icon.filter=replacement;
  test.draw(chain);icon.night=true;test.draw(chain);
  check(icon.filter==replacement,"restore the latest native filter");
  icon.night=false;test.draw(chain);icon.id=3;test.draw(chain);
  check(icon.filter==replacement,"view ID reuse must release styling");
  ImageView empty=new ImageView();Chain emptyChain=new Chain(empty);test.draw(emptyChain);
  empty.night=true;test.draw(emptyChain);check(empty.filter==null,"null original filter must restore");
  for(Listener listener: new Listener[]{LISTENER_INSTANCES}) {
   View view=new View();listener.onViewAttachedToWindow(view);listener.onViewAttachedToWindow(view);
   check(view.observer.listeners.size()==1,"attach during native callback must register once");
   listener.onViewDetachedFromWindow(view);check(view.observer.listeners.isEmpty(),"detach must remove listener");
   listener.onViewAttachedToWindow(view);check(view.observer.listeners.size()==1,"reattach must register again");
   listener.onViewDetachedFromWindow(view);
  }
  System.out.println("PASS: 1000 stable draws produce one tint write; theme/native/ID restoration; four listener lifecycles");
 }
}'''.replace('DRAW_BODY',draw).replace('IDENTIFY_BODY',identify)
listeners=[]
names=['NotificationAppearanceHooks','TileCornerHooks','CombinedNotificationWidthHooks','ColorOsMaterialHooks']
for index,name in enumerate(names):
    text=(source/f'{name}.java').read_text()
    methods=[]
    for method in ['onViewAttachedToWindow','onViewDetachedFromWindow']:
        signature=re.search(r'public void '+method+r'\(View \w+\)',text).group()
        methods.append(signature+block(text,signature))
    listeners.append(f'static class State{index} implements Listener {{Observer observer;boolean failed,added;void update(){{}}'+''.join(methods)+'}')
java=java.replace('LISTENER_CLASSES','\n'.join(listeners)).replace('LISTENER_INSTANCES',','.join(f'new State{i}()' for i in range(len(names))))
with tempfile.TemporaryDirectory(prefix='flyme-draw-lifecycle-',dir='/private/tmp') as directory:
    path=Path(directory)/'DrawLifecycleTest.java'
    path.write_text(java)
    subprocess.run([str(jdk/'javac'),'-d',directory,str(path)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',directory,'DrawLifecycleTest'],check=True)
