"""Execute production media clip/uniform code with affine viewport fixtures."""
from pathlib import Path
import subprocess,tempfile
src=Path('app/src/main/java/dev/rikumi/flymemod/TileBackdropHooks.java').read_text()
shader=src[src.index('    static final String SHADER'):src.index('    private final Class<?> mediaClass')]
methods=src[src.index('        private void addMediaClip'):src.rindex('\n    }')]
code='''import java.util.*;import java.lang.reflect.*;
public class MediaBackdropTest {
static class RectF {float left,top,right,bottom;void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}void setEmpty(){set(0,0,0,0);}boolean intersect(float l,float t,float r,float b){if(Math.max(left,l)>=Math.min(right,r)||Math.max(top,t)>=Math.min(bottom,b))return false;set(Math.max(left,l),Math.max(top,t),Math.min(right,r),Math.min(bottom,b));return true;}}
static class Matrix {float sx=1,sy=1,tx,ty;void mapRect(RectF r){r.set(r.left*sx+tx,r.top*sy+ty,r.right*sx+tx,r.bottom*sy+ty);}void mapVectors(float[] r){r[0]*=sx;r[1]*=sy;}}
public static class Effect {int writes;Map<String,float[]> values=new HashMap<>();public void uniform(String n,float a,float b,float c,float d){values.put(n,new float[]{a,b,c,d});writes++;}}
final RectF bounds=new RectF(),visibleBounds=new RectF(),ancestorBounds=new RectF();
final Matrix fromAncestor=new Matrix();final float[] mappedMediaRadius=new float[2];
final RectF[] mediaClips={new RectF(),new RectF(),new RectF(),new RectF()};
final float[][] mediaClipRadii=new float[4][4],appliedMediaClips=new float[4][8];int mediaClipCount;
final Effect effect=new Effect();final Method setFloat4;
MediaBackdropTest()throws Exception{setFloat4=Effect.class.getMethod("uniform",String.class,float.class,float.class,float.class,float.class);for(float[] a:appliedMediaClips)Arrays.fill(a,Float.NaN);}
static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
''' +shader+methods+'''
public static void main(String[] args)throws Exception{
MediaBackdropTest t=new MediaBackdropTest();t.bounds.set(0,0,200,100);t.visibleBounds.set(0,0,200,100);
t.addMediaClip(0,0,200,80,20);t.updateMediaUniforms();
check(t.visibleBounds.bottom==80);check(Arrays.equals(t.effect.values.get("mediaRadius0"),new float[]{20,20,20,20}));
int writes=t.effect.writes;t.updateMediaUniforms();check(t.effect.writes==writes);
t.mediaClipCount=0;t.visibleBounds.set(0,0,200,100);t.fromAncestor.sx=.5f;t.fromAncestor.sy=2;t.fromAncestor.tx=-10;
t.addMediaClip(20,0,420,40,16,8);t.updateMediaUniforms();
check(Arrays.equals(t.effect.values.get("mediaClip0"),new float[]{0,0,200,80}));
check(Arrays.equals(t.effect.values.get("mediaRadius0"),new float[]{8,32,4,16}));
t.mediaClipCount=0;t.updateMediaUniforms();check(Arrays.equals(t.effect.values.get("mediaClip0"),new float[4]));check(Arrays.equals(t.effect.values.get("mediaRadius0"),new float[4]));
t.visibleBounds.set(0,0,200,100);for(int i=0;i<5;i++)t.addMediaClip(0,0,200,100,10);check(t.visibleBounds.right==0);
check(MEDIA_SHADER.contains("float2 mediaLocal"));check(MEDIA_SHADER.contains("*mediaCoverage(mediaLocal,mediaClip3,mediaRadius3)"));check(!SHADER.contains("mediaCoverage"));
System.out.println("PASS: "+checks+" production media backdrop clipping, transform, uniform cache and shader checks");}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 p=Path(tmp)/'MediaBackdropTest.java';p.write_text(code)
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac','-d',tmp,str(p)],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'MediaBackdropTest'],check=True)
