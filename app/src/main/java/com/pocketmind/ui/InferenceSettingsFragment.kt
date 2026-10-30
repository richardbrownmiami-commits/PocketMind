package com.pocketmind.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pocketmind.R
import com.pocketmind.engine.InferenceSettings
import com.pocketmind.engine.InferenceSettingsStore
import com.pocketmind.engine.PromptTemplate
import com.pocketmind.tools.AppLog
import java.util.Locale

class InferenceSettingsFragment : Fragment(R.layout.fragment_inference_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.info(requireContext(), "INFERENCE_SETTINGS_SCREEN_OPENED")
        val store = InferenceSettingsStore(requireContext())
        val saved = store.load()

        val temperatureSlider = view.findViewById<Slider>(R.id.temperature_slider).apply {
            valueFrom = InferenceSettingsStore.MIN_TEMPERATURE
            valueTo = InferenceSettingsStore.MAX_TEMPERATURE
            stepSize = 0.1f
            value = saved.temperature
        }
        val temperatureValue = view.findViewById<TextView>(R.id.temperature_value)
        temperatureValue.text = String.format(Locale.US, "%.1f", temperatureSlider.value)
        temperatureSlider.addOnChangeListener { _, value, _ ->
            temperatureValue.text = String.format(Locale.US, "%.1f", value)
        }

        val topKSlider = view.findViewById<Slider>(R.id.top_k_slider).apply {
            valueFrom = InferenceSettingsStore.MIN_TOP_K.toFloat()
            valueTo = InferenceSettingsStore.MAX_TOP_K.toFloat()
            stepSize = 1f
            value = saved.topK.toFloat()
        }
        val topKValue = view.findViewById<TextView>(R.id.top_k_value)
        topKValue.text = topKSlider.value.toInt().toString()
        topKSlider.addOnChangeListener { _, value, _ -> topKValue.text = value.toInt().toString() }

        val topPSlider = view.findViewById<Slider>(R.id.top_p_slider).apply {
            valueFrom = InferenceSettingsStore.MIN_TOP_P
            valueTo = InferenceSettingsStore.MAX_TOP_P
            stepSize = 0.05f
            value = saved.topP
        }
        val topPValue = view.findViewById<TextView>(R.id.top_p_value)
        topPValue.text = String.format(Locale.US, "%.2f", topPSlider.value)
        topPSlider.addOnChangeListener { _, value, _ ->
            topPValue.text = String.format(Locale.US, "%.2f", value)
        }

        val templateOptions = resources.getStringArray(R.array.inference_prompt_templates).toList()
        val templateDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.prompt_template_dropdown).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, templateOptions))
            setText(templateOptions[saved.promptTemplate.id], false)
        }
        val customTemplateContainer = view.findViewById<View>(R.id.custom_template_container)
        val customTemplateLayout = view.findViewById<TextInputLayout>(R.id.custom_template_layout)
        val customTemplateInput = view.findViewById<TextInputEditText>(R.id.custom_template_input).apply {
            setText(saved.customPromptTemplate)
        }
        fun updateCustomTemplateVisibility() {
            val selectedIndex = templateOptions.indexOf(templateDropdown.text.toString())
            customTemplateContainer.visibility = if (selectedIndex == PromptTemplate.CUSTOM.id) View.VISIBLE else View.GONE
            customTemplateLayout.error = null
        }
        templateDropdown.setOnItemClickListener { _, _, _, _ -> updateCustomTemplateVisibility() }
        updateCustomTemplateVisibility()

        val contextOptions = InferenceSettings.CONTEXT_OPTIONS.map(Int::toString)
        val contextDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.context_dropdown).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, contextOptions))
            setText(saved.contextTokens.toString(), false)
        }

        val threadOptions = (1..InferenceSettings.availableCpuThreads()).map(Int::toString)
        val cpuThreadsDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.cpu_threads_dropdown).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, threadOptions))
            setText(saved.cpuThreads.coerceAtMost(threadOptions.size).toString(), false)
        }

        view.findViewById<TextView>(R.id.save_inference_settings).setOnClickListener {
            val templateIndex = templateOptions.indexOf(templateDropdown.text.toString())
                .takeIf { it >= 0 } ?: saved.promptTemplate.id
            val promptTemplate = PromptTemplate.fromId(templateIndex)
            val customPromptTemplate = customTemplateInput.text?.toString().orEmpty()
            if (promptTemplate == PromptTemplate.CUSTOM && !InferenceSettings.isValidCustomPromptTemplate(customPromptTemplate)) {
                customTemplateLayout.error = getString(R.string.inference_custom_template_invalid)
                customTemplateInput.requestFocus()
                return@setOnClickListener
            }
            customTemplateLayout.error = null
            val contextTokens = contextDropdown.text.toString().toIntOrNull()
                ?.takeIf { it in InferenceSettings.CONTEXT_OPTIONS } ?: saved.contextTokens
            val cpuThreads = cpuThreadsDropdown.text.toString().toIntOrNull()
                ?.takeIf { it in 1..threadOptions.size } ?: saved.cpuThreads.coerceAtMost(threadOptions.size)
            store.save(
                InferenceSettings(
                    temperature = temperatureSlider.value,
                    topK = topKSlider.value.toInt(),
                    topP = topPSlider.value,
                    contextTokens = contextTokens,
                    cpuThreads = cpuThreads,
                    promptTemplate = promptTemplate,
                    customPromptTemplate = customPromptTemplate
                )
            )
            AppLog.info(
                requireContext(),
                "INFERENCE_SETTINGS_SAVED",
                "temperature=${temperatureSlider.value} topK=${topKSlider.value.toInt()} topP=${topPSlider.value} contextTokens=$contextTokens cpuThreads=$cpuThreads template=${promptTemplate.name} customTemplateCharacters=${customPromptTemplate.length}"
            )
            view.findViewById<TextView>(R.id.inference_settings_message)
                .setText(R.string.inference_saved_message)
        }
    }
}
