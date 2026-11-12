package dev.rikumi.flymemod

import androidx.annotation.StringRes

internal data class SystemAppAnnotation(
    @StringRes val description: Int,
    @StringRes val recommendation: Int,
)

/** Only packages examined on Flyme 12.6.0.0A; no Pixel vendor or GSI compatibility labels. */
internal object SystemAppDescriptions {
    fun find(packageName: String): SystemAppAnnotation? = annotations[packageName]

    private fun keep(@StringRes description: Int) =
        SystemAppAnnotation(description, R.string.system_app_recommendation_keep)
    private fun possible(@StringRes description: Int) =
        SystemAppAnnotation(description, R.string.system_app_recommendation_possible)

    private val annotations = mapOf(
        "com.activate.activatephone" to possible(R.string.flyme_app_description_activation),
        "com.android.alarmclock" to possible(R.string.flyme_app_description_clock),
        "com.android.dialer" to keep(R.string.flyme_app_description_dialer),
        "com.android.documentsui" to keep(R.string.flyme_app_description_documents),
        "com.android.incallui" to keep(R.string.flyme_app_description_incall),
        "com.android.mms" to keep(R.string.flyme_app_description_messages),
        "com.android.packageinstaller" to keep(R.string.flyme_app_description_installer),
        "com.android.phone" to keep(R.string.flyme_app_description_telephony),
        "com.android.providers.downloads.ui" to possible(R.string.flyme_app_description_downloads_ui),
        "com.android.settings" to keep(R.string.flyme_app_description_settings),
        "com.android.systemui" to keep(R.string.flyme_app_description_system_ui),
        "com.flyme.alivewallpaper" to possible(R.string.flyme_app_description_alive_wallpaper),
        "com.flyme.netadmin" to possible(R.string.flyme_app_description_network_diagnostics),
        "com.flyme.safebox" to possible(R.string.flyme_app_description_safebox),
        "com.flyme.secureservice" to keep(R.string.flyme_app_description_secure_service),
        "com.flyme.smartcard" to possible(R.string.flyme_app_description_smart_card),
        "com.flyme.systemuieditor" to possible(R.string.flyme_app_description_appearance_editor),
        "com.flyme.systemuiex" to possible(R.string.flyme_app_description_screenshot),
        "com.flyme.systemuitools" to keep(R.string.flyme_app_description_system_ui_tools),
        "com.flyme.telecom.usagedata.service" to possible(R.string.flyme_app_description_telecom_statistics),
        "com.flyme.virtualdevice" to possible(R.string.flyme_app_description_hardware_sharing),
        "com.flyme.wordwallpaper.stroke" to possible(R.string.flyme_app_description_word_wallpaper),
        "com.meizu.account" to keep(R.string.flyme_app_description_account),
        "com.meizu.alphame" to possible(R.string.flyme_app_description_aicy_engine),
        "com.meizu.battery" to keep(R.string.flyme_app_description_battery),
        "com.meizu.callsetting" to keep(R.string.flyme_app_description_call_settings),
        "com.meizu.commdiagnostic" to possible(R.string.flyme_app_description_communication_diagnostics),
        "com.meizu.connectivitysettings" to keep(R.string.flyme_app_description_connectivity),
        "com.meizu.dataservice" to possible(R.string.flyme_app_description_experience_statistics),
        "com.meizu.earphone" to possible(R.string.flyme_app_description_earphones),
        "com.meizu.filemanager" to possible(R.string.flyme_app_description_file_manager),
        "com.meizu.flyme.launcher" to keep(R.string.flyme_app_description_launcher),
        "com.meizu.flyme.sdkstage" to keep(R.string.flyme_app_description_shared_components),
        "com.meizu.flyme.service.find" to possible(R.string.flyme_app_description_find_device),
        "com.meizu.location" to keep(R.string.flyme_app_description_network_location),
        "com.meizu.media.camera" to possible(R.string.flyme_app_description_camera),
        "com.meizu.media.gallery" to possible(R.string.flyme_app_description_gallery),
        "com.meizu.media.imageservice" to possible(R.string.flyme_app_description_image_processing),
        "com.meizu.mzbasestationsafe" to possible(R.string.flyme_app_description_sms_protection),
        "com.meizu.mznfcpay" to possible(R.string.flyme_app_description_flyme_pay),
        "com.meizu.mzsyncservice" to possible(R.string.flyme_app_description_cloud_sync),
        "com.meizu.net.search" to possible(R.string.flyme_app_description_search),
        "com.meizu.netcontactservice" to possible(R.string.flyme_app_description_number_identification),
        "com.meizu.perfui" to possible(R.string.flyme_app_description_performance_diagnostics),
        "com.meizu.powersave" to possible(R.string.flyme_app_description_power_saving_launcher),
        "com.meizu.privacy" to keep(R.string.flyme_app_description_privacy),
        "com.meizu.remotecooperation" to possible(R.string.flyme_app_description_remote_assistance),
        "com.meizu.safe" to keep(R.string.flyme_app_description_phone_manager),
        "com.meizu.sar" to keep(R.string.flyme_app_description_sar),
        "com.meizu.setup" to keep(R.string.flyme_app_description_setup),
        "com.meizu.share" to possible(R.string.flyme_app_description_share),
        "com.meizu.test.mzlinetest" to possible(R.string.flyme_app_description_factory_tests),
        "com.meizu.wallpapersetting" to possible(R.string.flyme_app_description_wallpaper_settings),
    )
}
