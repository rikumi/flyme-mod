"""Verify exact native management pages and special-app exclusions without Android state writes."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
code='''package dev.rikumi.flymemod;
public class AppManagementPolicyTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
 check(AppManagementBackgroundPolicy.page("com.meizu.safe","com.meizu.safe.ui.app.AppManagementActivity",null));
 check(!AppManagementBackgroundPolicy.page("com.meizu.safe","com.meizu.safe.ui.security.SecurityMoreActivity",null));
 for(String name:new String[]{"com.meizu.settings.MzSettingsActivity$AllAppsActivity","com.meizu.settings.MzSettingsActivity$DefaultAppsActivity","com.android.settings.applications.InstalledAppDetailsTop"})check(AppManagementBackgroundPolicy.page("com.android.settings",name,null));
 for(String name:new String[]{"ManageApplications","InstalledAppDetails","InstalledAppDetailsPermissionsDetails"})check(AppManagementBackgroundPolicy.page("com.android.settings","com.android.settings.SubSettings","com.meizu.settings.applications."+name));
 check(!AppManagementBackgroundPolicy.page("com.android.settings","com.android.settings.SubSettings","com.meizu.settings.MzDisplaySettings"));
 check(!AppManagementBackgroundPolicy.page("com.android.settings","com.android.settings.Settings",null));
 check(!AppManagementBackgroundPolicy.page("other","com.meizu.safe.ui.app.AppManagementActivity",null));
 for(String pkg:new String[]{"com.android.dialer","com.meizu.mznfcpay","com.android.vending","com.android.settings","com.android.alarmclock","com.meizu.media.gallery","com.meizu.filemanager","com.meizu.media.camera","com.android.mms","com.android.traceur","com.meizu.mstore"})check(NightModeSpecialPackagePolicy.nativeDark(pkg));
 for(String pkg:new String[]{"com.android.documentsui","com.android.shell","com.meizu.wear","com.meizu.account.pay","com.meizu.mstore.fake"})check(!NightModeSpecialPackagePolicy.nativeDark(pkg));
 System.out.println("PASS: "+checks+" exact management page and special-package policy checks");
 }}'''
with tempfile.TemporaryDirectory(prefix='flyme-app-management-',dir='/private/tmp') as d:
 p=Path(d)/'AppManagementPolicyTest.java';p.write_text(code)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',d,str(p),*[str(ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java') for n in ['AppManagementBackgroundPolicy','NightModeSpecialPackagePolicy']]],check=True)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',d,'dev.rikumi.flymemod.AppManagementPolicyTest'],check=True)
