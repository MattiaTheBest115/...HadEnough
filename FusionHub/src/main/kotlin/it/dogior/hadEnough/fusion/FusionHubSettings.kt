package it.dogior.hadEnough.fusion

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import it.dogior.hadEnough.BuildConfig

class FusionHubSettings(
    private val plugin: FusionHubPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {
    private var currentBaseUrl: String =
        FusionStreamingCommunitySource.normalizeBaseUrl(
            sharedPref.getString(FusionStreamingCommunitySource.PREF_BASE_URL, null)
        ) ?: ""
    private var currentAnimePriority: String =
        sharedPref.getString(
            FusionHub.PREF_ANIME_PROVIDER_PRIORITY,
            FusionHub.DEFAULT_ANIME_PROVIDER_PRIORITY,
        ) ?: FusionHub.DEFAULT_ANIME_PROVIDER_PRIORITY

    @SuppressLint("DiscouragedApi")
    private fun getStringResource(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerText: TextView? = view.findViewByName("header_tw")
        headerText?.text = getStringResource("header_tw")

        val serverAddressLabel: TextView? = view.findViewByName("server_address_label")
        serverAddressLabel?.text = getStringResource("server_address_label")

        val serverAddressInput: EditText? = view.findViewByName("server_address_input")
        serverAddressInput?.hint = getStringResource("server_address_hint")
        serverAddressInput?.setText(currentBaseUrl)

        val animePriorityLabel: TextView? = view.findViewByName("anime_priority_label")
        animePriorityLabel?.text = getStringResource("anime_priority_label")

        val animePrioritySpinner: Spinner? = view.findViewByName("anime_priority_spinner")
        val animePriorityValues = listOf(FusionHub.ANIMEUNITY_PRIORITY, FusionHub.ANIMEWORLD_PRIORITY)
        val animePriorityLabels = animePriorityValues.map { value ->
            when (value) {
                FusionHub.ANIMEWORLD_PRIORITY -> getStringResource("anime_priority_animeworld")
                else -> getStringResource("anime_priority_animeunity")
            } ?: value
        }
        animePrioritySpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            animePriorityLabels,
        )
        animePrioritySpinner?.setSelection(
            animePriorityValues.indexOf(currentAnimePriority).takeIf { it >= 0 } ?: 0
        )
        animePrioritySpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                selectedView: View?,
                position: Int,
                id: Long,
            ) {
                currentAnimePriority = animePriorityValues[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val saveButton: Button? = view.findViewByName("save_btn")
        saveButton?.text = getStringResource("save_btn")
        saveButton?.setOnClickListener {
            val rawBaseUrl = serverAddressInput?.text?.toString()?.trim().orEmpty()
            val normalizedBaseUrl = FusionStreamingCommunitySource.normalizeBaseUrl(rawBaseUrl)

            val toastMessage = when {
                rawBaseUrl.isBlank() -> {
                    sharedPref.edit()
                        .remove(FusionStreamingCommunitySource.PREF_BASE_URL)
                        .putString(FusionHub.PREF_ANIME_PROVIDER_PRIORITY, currentAnimePriority)
                        .apply()
                    currentBaseUrl = ""
                    getStringResource("default_restored")
                }

                normalizedBaseUrl.isNullOrBlank() -> {
                    sharedPref.edit()
                        .remove(FusionStreamingCommunitySource.PREF_BASE_URL)
                        .putString(FusionHub.PREF_ANIME_PROVIDER_PRIORITY, currentAnimePriority)
                        .apply()
                    currentBaseUrl = ""
                    getStringResource("invalid_url_fallback")
                }

                else -> {
                    sharedPref.edit()
                        .putString(FusionStreamingCommunitySource.PREF_BASE_URL, normalizedBaseUrl)
                        .putString(FusionHub.PREF_ANIME_PROVIDER_PRIORITY, currentAnimePriority)
                        .apply()
                    currentBaseUrl = normalizedBaseUrl
                    getStringResource("settings_saved")
                }
            } ?: "Impostazioni salvate. Riavvia app per applicarle"

            showToast(toastMessage)
            dismiss()
        }
    }
}
