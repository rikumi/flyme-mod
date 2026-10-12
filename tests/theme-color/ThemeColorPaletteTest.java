package dev.rikumi.flymemod;
import java.util.Random;
public class ThemeColorPaletteTest {
    public static void main(String[] args) {
        int checks = 0;
        int[] sources = {0x206cff,0x1564ed,0x1368fb,0x198ded,0xe1ecfe,0xd0e1fe,
            0xa3c4fe,0x639bfd,0x3b82fc,0x095ef1,0x0d57d9,0x1051c1,0x124aab,0x172747};
        for (int target : new int[]{0xffe04080,0xff1368fb,0xff206cff,0xff1564ed,0xff000000,0xffffffff,0xffd0e1fe}) {
            ThemeColorPalette on = new ThemeColorPalette(true, target);
            ThemeColorPalette off = new ThemeColorPalette(false, target);
            for (int rgb : sources) for (int alpha=0; alpha<256; alpha++) {
                int original = alpha<<24 | rgb;
                int mapped = on.replace(original);
                check((mapped >>> 24)==alpha, "alpha");
                check(on.replace(mapped)==mapped, "idempotence");
                check(off.replace(original)==original, "disabled");
                checks+=3;
            }
            Random random = new Random(42);
            for(int i=0;i<10000;i++) {
                int original=random.nextInt();
                boolean matched=false;
                for(int rgb:sources) if((original & 0xffffff)==rgb) matched=true;
                if(!matched) check(on.replace(original)==original, "unmatched RGB");
                checks++;
            }
        }
        ThemeColorPalette pink = new ThemeColorPalette(true, 0xffe04080);
        check(pink.replace(0x40206cff)==0x40e04080, "translucent primary");
        check(pink.replace(0xffe1ecfe)!=0xffe04080, "light container distinct");
        check(pink.replace(0xff172747)!=0xffe04080, "dark container distinct");
        check(!ThemeColorTargets.recommended().contains("com.flyme.secureservice"), "background target not recommended");
        check(ScopeRequirements.hasMissing(java.util.Set.of("system", "app.a", "app.b"),
                java.util.Set.of("app.a"), java.util.Set.of("android")), "missing installed scope");
        check(!ScopeRequirements.hasMissing(java.util.Set.of("system", "app.a", "app.b"),
                java.util.Set.of("app.a"), java.util.Set.of("android", "app.a")), "ignore absent packages and normalize system");
        check(ScopeRequirements.hasMissing(java.util.Set.of("system"),
                java.util.Set.of(), java.util.Set.of()), "missing system scope");
        int[] extracted = ThemeColorTargets.colors();
        check(extracted.length > 14, "expanded extracted palette");
        check(ThemeColorTargets.contains("com.android.settings"), "Settings target");
        check(!ThemeColorTargets.contains("com.meizu.media.camera"), "Camera excluded");
        check(ThemeColorTargets.contains("com.meizu.flyme.weather"), "Weather retains pure-black target");
        check(ThemeColorTargets.colorExcluded("com.meizu.flyme.weather"), "Weather theme color disabled");
        check(ThemeColorTargets.recommended().contains("com.meizu.flyme.weather"), "Weather remains scoped");
        check(!ThemeColorTargets.contains("com.meizu.media.music"), "QQMusic injection excluded");
        check(!ThemeColorTargets.recommended().contains("com.meizu.media.music"), "QQMusic scope excluded");
        check(ThemeColorTargets.isColorResource("com.meizu.notepaper", "color_ffbe0a"), "Note search and AI accent included");
        check(ThemeIconPolicy.mode("com.meizu.notepaper", "bottom_tab_note_select") == ThemeIconPolicy.COLOR_PARTS,
                "Note selected navigation icon included");
        check(ThemeColorTargets.recommended().contains("com.meizu.media.camera"), "Camera scope required for other module hooks");
        check(ThemeIconPolicy.mode("com.meizu.media.camera", "mz_ic_tab_menu") == ThemeIconPolicy.NONE, "Camera icons excluded");
        check(ThemeIconPolicy.mode("com.android.settings", "mz_function_list_ic_aicy") == ThemeIconPolicy.COLOR_PARTS, "Settings AI icon");
        check(ThemeIconPolicy.mode("com.meizu.mstore", "ai_loading_icon_colorful") == ThemeIconPolicy.COLOR_PARTS, "Store AI icon");
        check(ThemeIconPolicy.mode("com.meizu.filemanager", "ic_avatar") == ThemeIconPolicy.NONE, "Avatar excluded");
        check(!ThemeColorTargets.isColorResource("com.meizu.media.camera", "theme_primary"), "Camera resources excluded");
        check(!ThemeColorTargets.contains("org.telegram.messenger"), "unrelated app excluded");
        check(!ThemeColorTargets.contains("dev.rikumi.flymemod"), "module editor excluded");
        for (int target : new int[]{0xffe04080, 0xff206cff, 0xff000000, 0xffffffff}) {
            ThemeColorPalette expanded = new ThemeColorPalette(true, target, extracted);
            ThemeColorPalette disabled = new ThemeColorPalette(false, target, extracted);
            for (int rgb : extracted) for (int alpha = 0; alpha < 256; alpha++) {
                int original = alpha << 24 | rgb;
                int mapped = expanded.replace(original);
                check((mapped >>> 24) == alpha, "extracted alpha");
                check(expanded.replace(mapped) == mapped, "extracted idempotence");
                check(disabled.replace(original) == original, "extracted disabled");
                checks += 3;
            }
        }
        ThemeColorPalette neutral = new ThemeColorPalette(true, 0xffaaaaaa, extracted);
        for (int primary : new int[]{0xff35c44f, 0xff29b15b, 0xffe42d22, 0xffba1a1a, 0xffffbe0a, 0xffdca50c}) {
            check(neutral.replace(primary) == 0xffaaaaaa, "green/red/yellow primary equals selected color");
        }
        check(neutral.replace(0x4035c44f) == 0x40aaaaaa, "green transparency");
        check(neutral.replace(0x80e42d22) == 0x80aaaaaa, "red transparency");
        check(neutral.replace(0x1affbe0a) == 0x1aaaaaaa, "yellow transparency");
        check(neutral.replaceResource(0x80ff7024, true) == 0x80aaaaaa, "Named orange primary and alpha");
        check(neutral.replaceResource(0xff808080, true) == 0xff808080, "Neutral state preserved");
        check(neutral.replaceResource(0xffffffff, false) == 0xffffffff, "White details preserved");
        check(new ThemeColorPalette(false, 0xffaaaaaa).replaceResource(0xff722ae4, true) == 0xff722ae4, "Disabled named resource");
        ThemeColorPalette installer = new ThemeColorPalette(true, 0xffaaaaaa, extracted, ThemeColorTargets.themeFamily("com.android.packageinstaller"));
        check(installer.replaceResource(0xff35c44f, true) == 0xffaaaaaa, "Installer primary green");
        check(installer.replaceResource(0x4029b15b, true) == 0x40aaaaaa, "Installer night/disabled green");
        check(installer.replaceResource(0xff1368fb, true) == 0xff1368fb, "Installer only green family");
        check(ThemeColorTargets.isColorResource("com.android.settings", "mz_btn_color_state"), "Theme attribute switch selector included");
        ThemeColorPalette launcher = new ThemeColorPalette(true, 0xff722ae4, extracted, ThemeColorTargets.themeFamily("com.meizu.flyme.launcher"));
        check(launcher.replaceResource(0xffe42d22, true) == 0xffe42d22, "Launcher notification red preserved");
        check(launcher.replaceResource(0xff1368fb, true) == 0xff722ae4, "Launcher blue primary replaced");
        check(launcher.replaceResource(0x40e42d22, false) == 0x40e42d22, "Launcher translucent red preserved");
        check(ThemeIconPolicy.mode("com.meizu.flyme.launcher", "ic_notification_count") == ThemeIconPolicy.NONE, "Badge icon excluded");
        for (int family = 1; family <= 5; family++) {
            ThemeColorPalette limited = new ThemeColorPalette(true, 0xff722ae4, extracted, family);
            for (int color : new int[]{0xff1368fb, 0xff35c44f, 0xffe42d22, 0xffffbe0a, 0xffff7024}) {
                int mapped = limited.replaceResource(color, true);
                check(mapped == (ThemeColorPalette.family(color) == family ? 0xff722ae4 : color), "Only one application theme family");
            }
        }
        check(new ThemeColorPalette(true, 0xff722ae4, extracted, 0).replaceResource(0xff1368fb, true) == 0xff1368fb, "Unresolved theme preserves native color");
        for (int shade : DarkBackgroundPolicy.SHADES) for (int alpha = 0; alpha < 256; alpha++) {
            int color = alpha << 24 | shade;
            check(DarkBackgroundPolicy.replace(color, true, true) == alpha << 24, "Both Flyme background generations become black");
            check(DarkBackgroundPolicy.replace(color, false, true) == color, "Black backgrounds default off");
            check(DarkBackgroundPolicy.replace(color, true, false) == color, "Light mode preserved");
        }
        for (int other : new int[]{0xff17171a, 0xff1c1c1e, 0xff2d2d2d, 0xff202020, 0xffffffff, 0xff808080, 0xff1368fb, 0xffe42d22, 0xff454545})
            check(DarkBackgroundPolicy.replace(other, true, true) == other, "Non-background shades preserved");
        check(DarkBackgroundPolicy.resource("fd_sys_color_surface_default"), "New and old base surface");
        check(!DarkBackgroundPolicy.resource("fd_sys_color_surface_container_lowest_default"), "Group surface preserved");
        check(!DarkBackgroundPolicy.resource("localColorSurfaceContainerLowest"), "Store group surface preserved");
        check(DarkBackgroundPolicy.resource("activity_background_color"), "Store activity background");
        check(DarkBackgroundPolicy.resource("white_gradient_center_color"), "Store gradient backdrop");
        check(!DarkBackgroundPolicy.resource("fd_sys_color_inverse_on_surface_default"), "Foreground surface excluded");
        check(!DarkBackgroundPolicy.resource("tab_text_background_color"), "Text resource excluded");
        check(ThemeIconPolicy.mode("com.meizu.filemanager", "ic_garbage_empty") == ThemeIconPolicy.NONE, "File manager recycling excluded");
        check(ThemeIconPolicy.mode("com.meizu.filemanager", "ic_garbage") == ThemeIconPolicy.NONE, "File manager recycling excluded");
        for (String name : new String[]{"ic_shortcut_clean", "ic_folder_fastfolder_all", "ic_folder_fastfolder_locked",
                "ic_folder_fastfolder_add_night", "mz_ic_list_music_big", "ic_storage"})
            check(ThemeIconPolicy.mode("com.meizu.filemanager", name) == ThemeIconPolicy.NONE, "Large file manager entry preserved");
        check(ThemeIconPolicy.mode("com.meizu.filemanager", "mz_ic_list_music_small") == ThemeIconPolicy.NONE, "Small file manager icon excluded");
        check(ThemeTabPolicy.galleryText("com.meizu.media.gallery", "tab2_name"), "Gallery selected tab label");
        check(ThemeTabPolicy.galleryIcon("com.meizu.media.gallery", "album1"), "Gallery animated tab icon");
        check(!ThemeTabPolicy.galleryIcon("com.meizu.media.gallery", "photo_thumbnail"), "Gallery content photo excluded");
        check(ThemeIconPolicy.mode("com.meizu.mstore", "mz_ac_tab_classify_focus") == ThemeIconPolicy.WHITE_CUTOUT, "Store focused tab cutout");
        check(ThemeIconPolicy.mode("com.meizu.mstore", "start_ac_tab_app_focus") == ThemeIconPolicy.WHITE_CUTOUT, "Store alternate tab cutout");
        check(ThemeIconPolicy.mode("com.android.settings", "mz_function_list_ic_aicy") == ThemeIconPolicy.COLOR_PARTS, "AI white details retained");
        check(ThemeTabPolicy.storeIcon("com.meizu.mstore", "fixed_bottom_navigation_icon"), "Store animated tab image");
        check(!ThemeTabPolicy.storeIcon("com.meizu.mstore", "app_icon"), "Store application images excluded");
        check(ThemeTabPolicy.storeGlobalBackground("com.meizu.mstore", "bottom_navigation_bar_container"), "Store navigation background");
        check(!ThemeTabPolicy.storeGlobalBackground("com.meizu.mstore", "app_detail_app_info_bg"), "Store card background excluded");
        check(DarkBackgroundPolicy.replaceStoreGlobal(0xff17171a, true, true) == 0xff000000, "Store page surface");
        check(DarkBackgroundPolicy.replaceStoreGlobal(0xff1c1c1e, true, true) == 0xff000000, "Store navigation global surface");
        check(DarkBackgroundPolicy.replace(0xff1c1c1e, true, true) == 0xff1c1c1e, "Other group surfaces preserved");
        check(DarkBackgroundPolicy.replaceStoreGlobal(0xff17171a, true, false) == 0xff17171a, "Store light mode preserved");
        check(DarkBackgroundPolicy.replaceStoreGlobal(0xff17171a, false, true) == 0xff17171a, "Store native background when disabled");
        check(ThemeColorTargets.isColorResource("com.meizu.media.gallery", "gallery_top_table_tab_text_color"), "Gallery tab state list included");
        for (String name : new String[]{"ic_app_remove", "ic_app_uninstall_more_apps", "ic_remove_no_shadow",
                "ic_remove_red", "ic_stack_remove", "ic_trash_close", "ic_uninstall_no_shadow", "mz_titlebar_ic_delete"})
            check(ThemeIconPolicy.mode("com.meizu.flyme.launcher", name) == ThemeIconPolicy.NONE, "Launcher destructive action stays native");
        check(ThemeIconPolicy.mode("com.android.settings", "mz_titlebar_ic_delete") == ThemeIconPolicy.PRESERVE_RED, "Blue themed app preserves destructive red");
        check(ThemeIconPolicy.mode("com.android.packageinstaller", "ic_uninstall") == ThemeIconPolicy.PRESERVE_RED, "Green themed app preserves destructive red");
        check(ThemeIconPolicy.mode("com.meizu.media.gallery", "mz_titlebar_ic_delete") == ThemeIconPolicy.COLOR_PARTS, "Explicitly red themed gallery can recolor red");
        check(ThemeIconPolicy.mode("com.meizu.filemanager", "ic_garbage_empty") == ThemeIconPolicy.NONE, "File manager icon excluded");
        check(ThemeIconPolicy.mode("com.android.settings", "mz_function_list_ic_aicy") == ThemeIconPolicy.COLOR_PARTS, "Multicolor AI icon remains themed");
        check(ThemeColorTargets.legacyBackgroundFamily("com.android.alarmclock") == 1, "Clock has explicit old surface window background");
        check(ThemeColorTargets.legacyBackgroundFamily("com.meizu.mstore") == 1, "Store is verified old style");
        check(ThemeColorTargets.legacyBackgroundFamily("com.android.settings") == 0, "Settings common bundled style does not classify as old");
        check(DarkBackgroundPolicy.replaceLegacy(0xff17171a, true, true, 1) == 0xff000000, "Old clock background becomes black");
        check(DarkBackgroundPolicy.replaceLegacy(0xff202020, true, true, 1) == 0xff000000, "Old compatible background becomes black");
        check(DarkBackgroundPolicy.replaceLegacy(0xff17171a, true, true, 0) == 0xff17171a, "New style background layers preserved");
        check(DarkBackgroundPolicy.replaceLegacy(0xff1c1c1e, true, true, 1) == 0xff1c1c1e, "Old bright pages do not recolor group shade");
        check(ThemeIconPolicy.mode("com.meizu.mstore", "mz_ac_tab_app") == ThemeIconPolicy.INACTIVE_TAB, "Inactive store glyph preserves white");
        check(ThemeIconPolicy.mode("com.meizu.mstore", "mz_ac_tab_app_focus") == ThemeIconPolicy.WHITE_CUTOUT, "Active store glyph white cutout");
        ThemeColorPalette settings = new ThemeColorPalette(true, 0xffaaaaaa, extracted, 1);
        check(settings.replaceResource(0xff206cff, true) == 0xffaaaaaa, "Native settings switch track blue");
        check(settings.replaceResource(0x33206cff, true) == 0x33aaaaaa, "Native disabled switch alpha");
        check(settings.replaceResource(0x80198ded, true) == 0x80aaaaaa, "Blue icon edge alpha");
        check(settings.replaceResource(0xffffffff, true) == 0xffffffff, "Blue icon white detail preserved");
        check(settings.replaceResource(0xffe42d22, true) == 0xffe42d22, "Settings destructive red preserved");
        check(settings.replaceResource(0xff808080, true) == 0xff808080, "Settings native grey track preserved");
        check(ThemeIconPolicy.mode("com.android.packageinstaller", "update_logo") == ThemeIconPolicy.WHITE_CUTOUT, "Installer blue and white upgrade icon");
        check(ThemeIconPolicy.mode("com.android.packageinstaller", "update_logo_polestar") == ThemeIconPolicy.WHITE_CUTOUT, "Installer alternate upgrade icon");
        check(ThemeTabPolicy.installerUpgradeIcon("com.android.packageinstaller", "update_logo"), "Upgrade ImageView source binding");
        check(!ThemeTabPolicy.installerUpgradeIcon("com.meizu.mstore", "update_logo"), "Upgrade view restricted to installer");
        check(ThemeColorTargets.contains("com.meizu.connectivitysettings"), "Standalone connection settings target");
        check(ThemeColorTargets.recommended().contains("com.meizu.connectivitysettings"), "Standalone connection settings scope");
        check(ThemeColorTargets.legacyBackgroundFamily("com.meizu.connectivitysettings") == 1, "Connectivity window uses bright legacy surface");
        check(!ThemeColorTargets.contains("com.meizu.media.camera"), "Camera remains excluded from theme replacement");
        ThemeColorPalette customSources = new ThemeColorPalette(true, 0xffe04080, new int[]{0x00a1ff});
        check(customSources.replace(0xff00a1ff) == 0xffe04080, "use extracted RGB");
        check(customSources.replace(0xff206cff) == 0xff206cff, "unlisted RGB excluded");
        System.out.println("PASS: " + (checks+3) + " color checks");
    }
    static void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
}
