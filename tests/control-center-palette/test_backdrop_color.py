"""Evaluate production single/batched shader expressions for day/night backdrop colors."""
from pathlib import Path
import re
import subprocess
import tempfile
root=Path('app/src/main/java/dev/rikumi/flymemod')
hooks=(root/'TileBackdropHooks.java').read_text()
batch=(root/'TileBackdropBatch.java').read_text()
shader=re.search(r'static final String SHADER = """(.*?)""";',hooks,re.S).group(1)
start=batch.index('    private static String shader(int capacity)');end=batch.rfind('\n}')
code='class TileBackdropHooks {static final String SHADER = """\n'+shader+'\n""";}\npublic class ShaderTest {\n'+batch[start:end]+'\npublic static void main(String[]args){System.out.print(shader(4));}}'
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
    p=Path(tmp)/'ShaderTest.java';p.write_text(code)
    jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk/'javac'),'-d',tmp,str(p)],check=True)
    generated=subprocess.check_output([str(jdk/'java'),'-cp',tmp,'ShaderTest'],text=True)

class V(tuple):
    def __new__(cls,v):return super().__new__(cls,v if isinstance(v,(tuple,list,V)) else [v]*3)
    def __add__(a,b):return V([x+y for x,y in zip(a,V(b))])
    __radd__=__add__
    def __sub__(a,b):return V([x-y for x,y in zip(a,V(b))])
    def __mul__(a,b):return V([x*y for x,y in zip(a,V(b))])
    __rmul__=__mul__
def minimum(a,b):return V([min(x,y) for x,y in zip(V(a),V(b))])
def clamp(v,lo,hi):return V([max(lo,min(x,hi)) for x in v])
class Pixel:
    def __init__(self,rgb,alpha):self.rgb=V(rgb);self.a=alpha
sat_expr=re.search(r'half3 saturated = (.*?);',shader).group(1)
light_expr=re.search(r'half3 brightened = (.*?);',shader).group(1)
batch_expr=re.search(r'half3 rgb=(.*?);',generated).group(1)
checks=0
for night in [False,True]:
 for sat in [0,.5,1]:
  for alpha in [.25,.7,1]:
   for rgb in [[.1,.2,.3],[.4,.4,.4],[.8,.6,.3]]:
    bg=Pixel([x*alpha for x in rgb],alpha)
    lum=sum(x*y for x,y in zip(bg.rgb,[.2126,.7152,.0722]))
    env=dict(half3=V,clamp=clamp,min=minimum,background=bg,luminance=lum,saturationAmount=sat,brightnessBoost=.1 if night else 0)
    env['saturated']=eval(sat_expr,{'__builtins__':{}},env)
    single=eval(light_expr,{'__builtins__':{}},env)
    for coverage in [.2,.6,1]:
     env.update(bg=bg,lum=lum,coverage=coverage,amount=sat*coverage,boost=(.1 if night else 0)*coverage)
     merged=eval(batch_expr,{'__builtins__':{}},env)
     assert all(abs(x-y)<1e-7 for x,y in zip(single,merged));checks+=1
     assert all(0<=x<=alpha for x in merged);checks+=1
    if not night and rgb[0]==rgb[1]==rgb[2]:
     assert all(abs(x-y)<1e-7 for x,y in zip(single,bg.rgb));checks+=1
assert 'boost=max(boost,c*' in generated
assert 'maximumBrightnessBoost' in generated
for i in range(4):assert f'boost=max(boost,c*tile{i}_5.w)' in generated
print(f'PASS: {checks} production single/batch color and alpha checks; no light-mode grayscale brightening')
