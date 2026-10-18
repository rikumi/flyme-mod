"""Verify media clip transport through the production batch decoder and shader generator."""
from pathlib import Path
import subprocess,tempfile
base=Path('app/src/main/java/dev/rikumi/flymemod')
hooks=(base/'TileBackdropHooks.java').read_text();batch=(base/'TileBackdropBatch.java').read_text()
constants=hooks[hooks.index('    static final String SHADER'):hooks.index('    private final Class<?> mediaClass')]
decoder=batch[batch.index('    private static final class Tile'):batch.index('    // Include every member')]
generator=batch[batch.index('    // Include every member'):batch.rfind('\n}')]
code='''import java.util.*;
class TileBackdropHooks {'''+constants+'''}
public class MediaBatchTest {
static class Parcel {List<Object> data=new ArrayList<>();int cursor;int dataPosition(){return cursor;}void setDataPosition(int p){cursor=p;}int readInt(){return (Integer)data.get(cursor++);}String readString(){return (String)data.get(cursor++);}float readFloat(){return (Float)data.get(cursor++);}}
'''+decoder+generator+'''
static int count;static void check(boolean value){count++;if(!value)throw new AssertionError("check "+count);}
static Parcel packet(boolean media){Parcel p=new Parcel();p.data.add(0);p.data.add(1);p.data.add("shapeSize");p.data.add(200f);p.data.add(100f);p.data.add(0);p.data.add(media?8:0);if(media)for(int i=0;i<4;i++){p.data.add("mediaClip"+i);for(float v:new float[]{i,2,200,80})p.data.add(v);p.data.add("mediaRadius"+i);for(float v:new float[]{20,30,10,15})p.data.add(v);}return p;}
public static void main(String[] args){
Parcel p=packet(true);p.cursor=3;Tile tile=new Tile(p);check(p.cursor==3);
for(int i=0;i<4;i++){check(Arrays.equals(tile.mediaClips[i],new float[]{i,2,200,80}));check(Arrays.equals(tile.mediaRadii[i],new float[]{20,30,10,15}));}
Tile ordinary=new Tile(packet(false));for(int i=0;i<4;i++){check(Arrays.equals(ordinary.mediaClips[i],new float[4]));check(Arrays.equals(ordinary.mediaRadii[i],new float[4]));}
String shader=shader(4);check(!shader.contains("uniform float4 mediaClip"));check(!shader.contains("uniform float4 mediaRadius"));
for(int i=0;i<4;i++){check(shader.contains("uniform float4 tile"+i+"_17;"));check(shader.contains("c*=mediaCoverage(local,tile"+i+"_16,tile"+i+"_17)"));}
check(shader.contains("half4 bg=sksl_background.eval(p)"));
// Every original tile hit must also hit its enclosing group, including AA margins.
Random random=new Random(5);
for(int trial=0;trial<200;trial++){
 float[] group=new float[4];float[][] boxes=new float[4][4];
 for(int i=0;i<4;i++){float x=random.nextInt(200)-100,y=random.nextInt(200)-100;
 boxes[i]=new float[]{x,y,x+random.nextInt(100)+1,y+random.nextInt(100)+1};
 includeBounds(group,boxes[i][0],boxes[i][1],boxes[i][2],boxes[i][3],i==0);}
 for(float[] b:boxes)for(float x:new float[]{b[0]-1,b[0],b[2],b[2]+1})
 for(float y:new float[]{b[1]-1,b[1],b[3],b[3]+1})
 check(x>=group[0]-1&&x<=group[2]+1&&y>=group[1]-1&&y<=group[3]+1);
 includeBounds(group,1,2,3,4,true);check(Arrays.equals(group,new float[]{1,2,3,4}));
}
for(int capacity:new int[]{1,4,5,24,48}) {
 String generated=shader(capacity);
 for(int group=0;group<(capacity+3)/4;group++){
 check(generated.contains("uniform float4 group"+group+";"));
 check(generated.contains("inside(p,group"+group+")"));
 }
 long opens=generated.chars().filter(c->c=='{').count(),closes=generated.chars().filter(c->c=='}').count();
 check(opens==closes);
}
System.out.println("PASS: "+count+" production media batch decode and mask transport checks");}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 path=Path(tmp)/'MediaBatchTest.java';path.write_text(code)
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac','-d',tmp,str(path)],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'MediaBatchTest'],check=True)
