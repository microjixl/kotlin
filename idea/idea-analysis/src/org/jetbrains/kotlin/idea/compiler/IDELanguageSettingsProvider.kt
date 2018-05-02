/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.compiler

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.analyzer.LanguageSettingsProvider
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.cli.common.arguments.Jsr305Parser
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.KotlinFacetSettingsProvider
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.TargetPlatformVersion
import org.jetbrains.kotlin.idea.caches.project.LibraryInfo
import org.jetbrains.kotlin.idea.caches.project.ModuleSourceInfo
import org.jetbrains.kotlin.idea.caches.project.ScriptDependenciesInfo
import org.jetbrains.kotlin.idea.caches.project.ScriptModuleInfo
import org.jetbrains.kotlin.idea.project.getLanguageVersionSettings
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.idea.project.targetPlatform
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromTemplate

object IDELanguageSettingsProvider : LanguageSettingsProvider {
    override fun getLanguageVersionSettings(moduleInfo: ModuleInfo, project: Project): LanguageVersionSettings =
        when {
            moduleInfo is ModuleSourceInfo -> moduleInfo.module.languageVersionSettings
            moduleInfo is LibraryInfo -> project.getLanguageVersionSettings(extraAnalysisFlags = getExtraAnalysisFlags(project))
            moduleInfo is ScriptModuleInfo -> getVersionLanguageSettingsForScripts(project, moduleInfo.scriptDefinition)
            moduleInfo is ScriptDependenciesInfo.ForFile ->
                getVersionLanguageSettingsForScripts(project, moduleInfo.scriptModuleInfo.scriptDefinition)
            else -> project.getLanguageVersionSettings()
        }

    private fun getExtraAnalysisFlags(project: Project): Map<AnalysisFlag<*>, Any?> {
        val map = mutableMapOf<AnalysisFlag<*>, Any>()
        for (module in ModuleManager.getInstance(project).modules) {
            val settings = KotlinFacetSettingsProvider.getInstance(project).getSettings(module) ?: continue
            val compilerArguments = settings.mergedCompilerArguments as? K2JVMCompilerArguments ?: continue

            val jsr305State = Jsr305Parser(MessageCollector.NONE).parse(
                compilerArguments.jsr305,
                compilerArguments.supportCompatqualCheckerFrameworkAnnotations
            )
            map.put(AnalysisFlag.jsr305, jsr305State)
        }
        return map
    }

    override fun getTargetPlatform(moduleInfo: ModuleInfo): TargetPlatformVersion {
        return (moduleInfo as? ModuleSourceInfo)?.module?.targetPlatform?.version ?: TargetPlatformVersion.NoVersion
    }
}

private val LANGUAGE_VERSION_SETTINGS = Key.create<CachedValue<LanguageVersionSettings>>("LANGUAGE_VERSION_SETTINGS")

private fun getVersionLanguageSettingsForScripts(project: Project, scriptDefinition: KotlinScriptDefinition): LanguageVersionSettings {
    val args = scriptDefinition.additionalCompilerArguments
    val scriptDefImpl = scriptDefinition as? KotlinScriptDefinitionFromTemplate
    return if (args.isEmpty() || scriptDefImpl == null) {
        project.getLanguageVersionSettings()
    } else {
        val settings = scriptDefImpl.getUserData(LANGUAGE_VERSION_SETTINGS) ?: createCachedValue(project) {
            val compilerArguments = K2JVMCompilerArguments()
            parseCommandLineArguments(args.toList(), compilerArguments)
            // TODO: reporting
            compilerArguments.configureLanguageVersionSettings(MessageCollector.NONE)
        }.also { scriptDefImpl.putUserData(LANGUAGE_VERSION_SETTINGS, it) }
        settings.value
    }
}

private fun createCachedValue(project: Project, body: () -> LanguageVersionSettings): CachedValue<LanguageVersionSettings> {
    return CachedValuesManager
        .getManager(project)
        .createCachedValue(
            {
                CachedValueProvider.Result(
                    body(),
                    ProjectRootModificationTracker.getInstance(project)
                )
            }, false
        )
}
