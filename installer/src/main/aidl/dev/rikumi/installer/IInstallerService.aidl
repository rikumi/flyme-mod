package dev.rikumi.installer;
import android.os.ParcelFileDescriptor;
interface IInstallerService {
    String install(in ParcelFileDescriptor apk, long size) = 0;
    void destroy() = 16777114;
}
