package dev.rikumi.flymemod;

/** One state source shared with Flyme's GoogleBasicServicePreferenceController. */
final class GoogleServiceTile {
    static final String SETTING = "cn_gms_core";
    static final String ACTION = "dev.rikumi.flymemod.action.GOOGLE_SERVICE_TILE";
    static final String PERMISSION = "dev.rikumi.flymemod.permission.CONTROL_GOOGLE_SERVICE";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String GOOGLE = "com.google.android.gms";
    static final String TOGGLE = "toggle";
    static final int NOT_INSTALLED = android.app.Activity.RESULT_FIRST_USER;
    static final int WRITE_FAILED = NOT_INSTALLED + 1;
    static final int LOCKED = NOT_INSTALLED + 2;

    private GoogleServiceTile() {}
}
