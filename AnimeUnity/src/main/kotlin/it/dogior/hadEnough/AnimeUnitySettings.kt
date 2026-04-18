package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlin.system.exitProcess

private data class HomeSectionFormState(
    val section: AnimeUnityHomeSection,
    var isEnabled: Boolean,
    var itemCount: Int,
)

class AnimeUnitySettings(
    private val plugin: AnimeUnityPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {
    private val sectionStates = mutableListOf<HomeSectionFormState>()

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    private fun getLayoutId(name: String): Int? {
        return plugin.resources?.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val layoutId = getLayoutId("settings")
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sectionsHeader: View? = view.findViewByName("sections_header")
        val sectionsContent: View? = view.findViewByName("sections_content")
        val sectionsIndicator: TextView? = view.findViewByName("sections_indicator")
        setupExpandableSection(sectionsHeader, sectionsContent, sectionsIndicator, true)

        val displayHeader: View? = view.findViewByName("display_header")
        val displayContent: View? = view.findViewByName("display_content")
        val displayIndicator: TextView? = view.findViewByName("display_indicator")
        setupExpandableSection(displayHeader, displayContent, displayIndicator, true)

        val homeSectionsContainer: LinearLayout? = view.findViewByName("home_sections_container")
        loadSectionStates()
        homeSectionsContainer?.let { renderSectionRows(it) }

        val scoreSwitch: SwitchMaterial? = view.findViewByName("score_switch")
        scoreSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true

        val dubSubSwitch: SwitchMaterial? = view.findViewByName("dub_sub_switch")
        dubSubSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true

        val episodeNumberSwitch: SwitchMaterial? = view.findViewByName("episode_number_switch")
        episodeNumberSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true

        val baseUrlInput: TextInputEditText? = view.findViewByName("base_url_input")
        baseUrlInput?.setText(AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref))

        val saveButton: MaterialButton? = view.findViewByName("save_btn")
        saveButton?.setOnClickListener {
            saveSettings(
                scoreEnabled = scoreSwitch?.isChecked ?: true,
                dubSubEnabled = dubSubSwitch?.isChecked ?: true,
                episodeNumberEnabled = episodeNumberSwitch?.isChecked ?: true,
                baseUrlInput = baseUrlInput,
                parentView = view,
            )
        }

        val resetButton: MaterialButton? = view.findViewByName("reset_btn")
        resetButton?.setOnClickListener {
            confirmReset()
        }
    }

    private fun setupExpandableSection(
        header: View?,
        content: View?,
        indicator: TextView?,
        expandedByDefault: Boolean,
    ) {
        if (header == null || content == null || indicator == null) return

        var isExpanded = expandedByDefault
        fun syncState() {
            content.visibility = if (isExpanded) View.VISIBLE else View.GONE
            indicator.text = if (isExpanded) "▾" else "▸"
        }

        syncState()
        header.setOnClickListener {
            isExpanded = !isExpanded
            syncState()
        }
    }

    private fun loadSectionStates() {
        sectionStates.clear()
        sectionStates += AnimeUnityPlugin.getOrderedSections(sharedPref).map { section ->
            HomeSectionFormState(
                section = section,
                isEnabled = sharedPref?.getBoolean(
                    AnimeUnityPlugin.getSectionEnabledPref(section),
                    true,
                ) ?: true,
                itemCount = AnimeUnityPlugin.getSectionItemCount(sharedPref, section),
            )
        }
    }

    private fun renderSectionRows(container: LinearLayout) {
        container.removeAllViews()

        sectionStates.forEachIndexed { index, state ->
            val layoutId = getLayoutId("home_section_item") ?: return@forEachIndexed
            val rowView = layoutInflater.inflate(
                plugin.resources?.getLayout(layoutId),
                container,
                false,
            )

            val titleView: TextView? = rowView.findViewByName("section_name")
            titleView?.text = state.section.title

            val positionView: TextView? = rowView.findViewByName("section_position")
            positionView?.text = formatString("position_label", index + 1)

            val enabledSwitch: SwitchMaterial? = rowView.findViewByName("section_switch")
            enabledSwitch?.isChecked = state.isEnabled
            enabledSwitch?.setOnCheckedChangeListener { _, isChecked ->
                state.isEnabled = isChecked
            }

            val countLayout: TextInputLayout? = rowView.findViewByName("section_count_layout")
            countLayout?.helperText = getString("item_count_helper")

            val countInput: TextInputEditText? = rowView.findViewByName("section_count_input")
            countInput?.setText(state.itemCount.toString())
            countInput?.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        val rawValue = s?.toString()?.trim().orEmpty()
                        if (rawValue.isBlank()) {
                            countLayout?.error = null
                            return
                        }

                        val parsedValue = rawValue.toIntOrNull()
                        if (parsedValue == null || parsedValue !in 1..30) {
                            countLayout?.error = getString("item_count_error")
                            return
                        }

                        countLayout?.error = null
                        state.itemCount = parsedValue
                    }
                }
            )

            val moveUpButton: MaterialButton? = rowView.findViewByName("move_up_btn")
            moveUpButton?.isEnabled = index > 0
            moveUpButton?.setOnClickListener {
                if (index > 0) {
                    sectionStates[index] = sectionStates[index - 1].also { sectionStates[index - 1] = sectionStates[index] }
                    renderSectionRows(container)
                }
            }

            val moveDownButton: MaterialButton? = rowView.findViewByName("move_down_btn")
            moveDownButton?.isEnabled = index < sectionStates.lastIndex
            moveDownButton?.setOnClickListener {
                if (index < sectionStates.lastIndex) {
                    sectionStates[index] = sectionStates[index + 1].also { sectionStates[index + 1] = sectionStates[index] }
                    renderSectionRows(container)
                }
            }

            container.addView(rowView)
        }
    }

    private fun saveSettings(
        scoreEnabled: Boolean,
        dubSubEnabled: Boolean,
        episodeNumberEnabled: Boolean,
        baseUrlInput: TextInputEditText?,
        parentView: View,
    ) {
        val baseUrlLayout: TextInputLayout? = parentView.findViewByName("base_url_layout")
        val rawBaseUrl = baseUrlInput?.text?.toString()?.trim().orEmpty()
        val normalizedBaseUrl = AnimeUnityPlugin.normalizeBaseUrl(rawBaseUrl)

        if (rawBaseUrl.isNotBlank() && normalizedBaseUrl == null) {
            baseUrlLayout?.error = getString("invalid_base_url")
            showToast(getString("invalid_base_url") ?: "Inserisci un link valido")
            return
        }

        baseUrlLayout?.error = null

        sharedPref?.edit {
            putString(
                AnimeUnityPlugin.PREF_SECTION_ORDER,
                sectionStates.joinToString(",") { state -> state.section.key },
            )

            sectionStates.forEach { state ->
                putBoolean(
                    AnimeUnityPlugin.getSectionEnabledPref(state.section),
                    state.isEnabled,
                )
                putInt(
                    AnimeUnityPlugin.getSectionItemCountPref(state.section),
                    state.itemCount.coerceIn(1, 30),
                )
            }

            putBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, scoreEnabled)
            putBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, dubSubEnabled)
            putBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, episodeNumberEnabled)

            if (normalizedBaseUrl.isNullOrBlank()) {
                remove(AnimeUnityPlugin.PREF_BASE_URL)
            } else {
                putString(AnimeUnityPlugin.PREF_BASE_URL, normalizedBaseUrl)
            }
        }

        promptRestart(isReset = false)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString("reset_confirm_title"))
            .setMessage(getString("reset_confirm_message"))
            .setNegativeButton(getString("cancel_button"), null)
            .setPositiveButton(getString("reset_confirm_button")) { _, _ ->
                sharedPref?.edit {
                    clear()
                }
                promptRestart(isReset = true)
            }
            .show()
    }

    private fun promptRestart(isReset: Boolean) {
        val titleKey = if (isReset) "reset_done_title" else "settings_saved_title"
        val messageKey = if (isReset) "reset_done_message" else "settings_saved_message"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(titleKey))
            .setMessage(getString(messageKey))
            .setNegativeButton(getString("restart_later")) { _, _ ->
                dismissAllowingStateLoss()
            }
            .setPositiveButton(getString("restart_now")) { _, _ ->
                restartApplication()
            }
            .setOnCancelListener {
                dismissAllowingStateLoss()
            }
            .show()
    }

    private fun restartApplication() {
        val context = requireContext().applicationContext
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val launchComponent = launchIntent?.component

        if (launchIntent == null || launchComponent == null) {
            showToast(
                getString("restart_unavailable")
                    ?: "Impossibile riavviare automaticamente l'app. Chiudila e riaprila manualmente."
            )
            dismissAllowingStateLoss()
            return
        }

        val restartIntent = Intent.makeRestartActivityTask(launchComponent)
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4815,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or immutableFlag,
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager != null) {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 150L,
                pendingIntent,
            )
        } else {
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(restartIntent)
        }

        dismissAllowingStateLoss()
        activity?.finishAffinity()
        exitProcess(0)
    }

    private fun formatString(name: String, value: Int): String {
        val template = getString(name) ?: "Posizione %d"
        return String.format(template, value)
    }
}
