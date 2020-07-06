
package com.github.rtwnt.language_data_set_generator

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.rtwnt.language_data.row.Value as ValueRow
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.text.DateFormat
import kotlin.random.Random

fun getCodeIdToLanguageIdMap(values: List<ValueRow>): Map<String, Set<String>> {
    return values.groupBy { it.codeId }
            .entries
            .associate { it.key to it.value.map { value -> value.languageId }.toSet() }
}

fun getProbabilitiesOfOccurenceOfDependentFeatures(codeIdToLanguageIdMap: Map<String, Set<String>>): Map<String, Map<String, Double>> {
    val probabilitiesOfCooccurrence = mutableMapOf<String, Map<String, Double>>()

    for (first in codeIdToLanguageIdMap.keys) {
        val probabilities = mutableMapOf<String, Double>()
        probabilitiesOfCooccurrence[first] = probabilities
        for (second in codeIdToLanguageIdMap.keys) {
            val langsWithFirstFeatureValue = codeIdToLanguageIdMap.getOrDefault(first, setOf())
            val langsWithSecondFeaturValue = codeIdToLanguageIdMap.getOrDefault(second, setOf())
            val langsWithBoth = langsWithFirstFeatureValue intersect langsWithSecondFeaturValue

            val divisor = langsWithFirstFeatureValue.size
            var probability = 0.0
            if (divisor != 0) {
                probability = langsWithBoth.size.toDouble().div(divisor)
            }
            probabilities[second] = probability
        }
    }

    return probabilitiesOfCooccurrence
}

data class FeatureSetGenerationConfig(val preselectedValueIds: Set<String>, val excludedValueIds: Set<String>, val minProbability: Double, val maxProbability: Double)

class FeatureSetGenerator(values: List<ValueRow>) {
    private var parameterIdsByCount: List<String>
    private var parameterIdToCodeIds: Map<String, Set<String>>
    private var probabilities: Map<String, Map<String, Double>>
    private var codeIdToLanguageId: Map<String, Set<String>>

    init {
        values.forEach {
            if (it.codeId.isNullOrBlank()) {
                error("Code id can't be null or empty")
            }

            if (it.parameterId.isNullOrBlank()) {
                error("Parameter id can't be null or empty")
            }

            if (it.languageId.isNullOrBlank()) {
                error("Language id can't be null or empty")
            }
        }
        this.codeIdToLanguageId = getCodeIdToLanguageIdMap(values)
        this.probabilities = getProbabilitiesOfOccurenceOfDependentFeatures(this.codeIdToLanguageId)
        this.parameterIdToCodeIds = values.groupBy { it.parameterId }
                .entries
                .associate { it.key to it.value.map { value -> value.codeId }.toSet() }
        this.parameterIdsByCount = values.groupBy { it.parameterId }
                .entries
                .map { it.key to it.value.map { value -> value.languageId }.size }
                .sortedByDescending { it.second }
                .map { it.first }
    }

    fun generateFeatureSet(config: FeatureSetGenerationConfig, random: Random): Set<String> {
        validateConfig(config)
        val selectedValueIds = config.preselectedValueIds.toMutableSet()
        val parameterIdToValueIdListForRandomChoice = mutableMapOf<String, Set<String>>()
        val paramIdsForPreselectedValues = parameterIdToCodeIds.filter { it.value.intersect(config.preselectedValueIds).isNotEmpty() }.keys
        val missingParameters = parameterIdsByCount.filter { it !in paramIdsForPreselectedValues }
        missingParameters.forEach {
            // probably impossible to happen due to all parameter and parameter value ids ultimately coming from data provided in values
            val nextParamValueIds = (parameterIdToCodeIds[it] ?: error("Couldn't find value ids for parameter id $it"))
                    .filter { value -> value !in config.excludedValueIds  }.toSet()
            val availableValues = nextParamValueIds.filter { codeId ->
                selectedValueIds.any { selected ->
                    // same as above
                    val prob = probabilities[selected]?.get(codeId) ?: error("Couldn't find probability for $codeId and $selected")
                    prob in config.minProbability..config.maxProbability
                }
            }
            var nextValue: String? = null
            if (availableValues.isNotEmpty()) {
                nextValue = availableValues.random(random)
            }
            if (nextValue == null) {
                parameterIdToValueIdListForRandomChoice[it] = nextParamValueIds
            } else {
                selectedValueIds.add(nextValue)
            }
        }
        parameterIdToValueIdListForRandomChoice.forEach { selectedValueIds.add(it.value.random(random)) }

        return selectedValueIds
    }

    private fun validateConfig(config: FeatureSetGenerationConfig) {
        if (parameterIdToCodeIds.any { it.value.intersect(config.preselectedValueIds).size > 1 }) {
            error("Each preselected value must belong to a different parameter")
        }

        if (parameterIdToCodeIds.any { config.excludedValueIds.containsAll(it.value)}) {
            error("Excluded values must not contain all values for any parameter")
        }

        if (!codeIdToLanguageId.keys.containsAll(config.preselectedValueIds)) {
            error("Preselected value ids contain ids of unknown values")
        }

        if (!codeIdToLanguageId.keys.containsAll(config.excludedValueIds)) {
            error("Excluded value ids contain ids of unknown values")
        }

        if (config.excludedValueIds.intersect(config.preselectedValueIds).isNotEmpty()){
            error("Excluded value ids must not be also preselected")
        }

        if (config.minProbability < 0.0) {
            error("Minimal probability cannot be less than 0.0")
        }

        if (config.maxProbability > 1.0) {
            error("Max probability cannot be more than 1.0")
        }

        if (config.minProbability > config.maxProbability) {
            error("Min probability cannot be larger than max probability")
        }
    }
}


const val NAME_KEY = "Name"
const val FAMILY_KEY = "Family"
const val AREA_KEY = "Area"
const val ID_KEY = "ID"
const val PARAMETER_ID_KEY = "Parameter_ID"
const val LANGUAGE_ID_KEY = "Language_ID"
const val CODE_ID_KEY = "Code_ID"

fun <K, V> Map<K, V>.getOrIllegalState(key: K): V {
    return this[key] ?: error("Missing $key value in $this")
}


data class Feature(val name: String, val area: String) {

    constructor(data: Map<String, String>):
        this(
                data.getOrIllegalState(NAME_KEY),
                data.getOrIllegalState(AREA_KEY)
        )

    companion object {
        fun readAllFromFile(path: String): Map<String, Feature> {
            val result = mutableMapOf<String, Feature>()
            csvReader().open(path) {
                readAllWithHeaderAsSequence().forEach { result[it.getOrIllegalState(ID_KEY)] = Feature(it) }
            }

            return result
        }
    }
}


data class FeatureValue(val name: String, val feature: Feature) {
    constructor(featureValueData: Map<String, String>, featureIdToFeature: Map<String, Feature>): this(
            featureValueData.getOrIllegalState(NAME_KEY),
            featureIdToFeature.getOrIllegalState(featureValueData.getOrIllegalState(PARAMETER_ID_KEY))
    )


    companion object {
        fun readFromFiles(featureValuePath: String, featurePath: String): Map<String, FeatureValue> {
            val features = Feature.readAllFromFile(featurePath)
            val result = mutableMapOf<String, FeatureValue>()
            csvReader().open(featureValuePath) {
                readAllWithHeaderAsSequence().forEach { result[it.getOrIllegalState(ID_KEY)] = FeatureValue(it, features) }
            }

            return result
        }
    }
}

data class Language(val name: String, val family: String, val featureValues: List<FeatureValue>){

    constructor(
            languageData: Map<String, String>,
            languageFeatureValueRelationshipData: List<Map<String, String>>,
            featureValueIdToFeatureValue: Map<String, FeatureValue>):
            this(
                    languageData.getOrIllegalState(NAME_KEY),
                    languageData.getOrIllegalState(FAMILY_KEY),
                    languageFeatureValueRelationshipData.filter {
                        it.getOrIllegalState(LANGUAGE_ID_KEY) == languageData.getOrIllegalState(ID_KEY)
                    }.map {
                        featureValueIdToFeatureValue.getOrIllegalState(it.getOrIllegalState(CODE_ID_KEY))
                    }
            )

    companion object {
        fun readFromFiles(
            languagePath: String,
            valuePath: String,
            featureValues: Map<String, FeatureValue>
        ): Map<String, Language> {
            val valueRows = mutableListOf<Map<String, String>>()
            csvReader().open(valuePath) {
                readAllWithHeaderAsSequence().forEach { valueRows.add(it) }
            }
            val result = mutableMapOf<String, Language>()
            csvReader().open(languagePath) {
                readAllWithHeaderAsSequence().forEach {
                    result[it.getOrIllegalState(ID_KEY)] = Language(it, valueRows, featureValues)
                }
            }
            return result
        }
    }
}

fun main(args: Array<String>) {
    val env = applicationEngineEnvironment {
        module {
            main(args)
        }
        connector {
            host = "0.0.0.0"
            port = 8080
        }
    }

    embeddedServer(Netty, env).start()
}

fun Application.main(args: Array<String>) {
    install(ContentNegotiation) {
        gson {
            setDateFormat(DateFormat.LONG)
            setPrettyPrinting()
        }
    }
    install(Routing) {
        get("/") {
            call.respondText("Language generator test", ContentType.Text.Plain)
        }
    }
}