"""Verify exact helper matching against the observed device component."""
from pathlib import Path
import subprocess, tempfile
source=Path("app/src/main/java/dev/rikumi/flymemod/SmallWindowLimitHooks.java").read_text()
method=source[source.index("    static boolean isMoreAppsComponent("):source.index("    private boolean enabled()")]
code="public class HelperTest {"+method+"""
public static void main(String[] args) {
 String pkg="com.flyme.systemuitools", prefix=pkg+".windowmode.views.";
 String[][] cases={
 {pkg,prefix+"SlideLaunchAppSettings",pkg,"true"},
 {pkg,prefix+"MoreAppWindow",pkg,"true"},
 {pkg,prefix+"SlideLaunchAppSettings","other","false"},
 {pkg,prefix+"SlideLaunchAppSettings",null,"false"},
 {"other",prefix+"SlideLaunchAppSettings",pkg,"false"},
 {pkg,prefix+"BootInstallActivity",pkg,"false"},
 {pkg,prefix+"OtherActivity",pkg,"false"},
 {null,null,null,"false"}};
 for(String[] c:cases) if(isMoreAppsComponent(c[0],c[1],c[2])!=Boolean.parseBoolean(c[3]))
 throw new AssertionError(java.util.Arrays.toString(c));
 System.out.println("PASS: 8 helper component and caller scope checks");
}}
"""
with tempfile.TemporaryDirectory(dir="/private/tmp") as tmp:
 p=Path(tmp)/"HelperTest.java";p.write_text(code)
 jdk="/opt/homebrew/opt/openjdk@21/bin/"
 subprocess.run([jdk+"javac",str(p)],check=True)
 subprocess.run([jdk+"java","-cp",tmp,"HelperTest"],check=True)
