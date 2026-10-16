package io.github.katznmeik.cinexperience.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails if a module group in [groups] is not allowed: every group must be in
 * [allowedGroups] or start with one of [allowedGroupPrefixes], and none may be in
 * [forbiddenGroups] (which overrides the prefixes). On success it writes the groups
 * to [report].
 *
 * The app registers it as `checkReleaseDependencyGroups` with the release runtime
 * classpath's groups ([resolvedModuleGroups]) and its allow and deny lists (spec
 * §11.1 rule 4), and every build depends on it.
 */
abstract class CheckDependencyGroupsTask : DefaultTask() {
    @get:Input
    abstract val groups: SetProperty<String>

    @get:Input
    abstract val allowedGroups: SetProperty<String>

    @get:Input
    abstract val allowedGroupPrefixes: ListProperty<String>

    @get:Input
    abstract val forbiddenGroups: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = forbiddenGroups.get()
        val unexpected = groups.get().filter { group ->
            group in forbidden || (group !in allowedGroups.get() && allowedGroupPrefixes.get().none { group.startsWith(it) })
        }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Release dependencies from groups that are not allowed: ${unexpected.sorted().joinToString()}. " +
                    "Review them (spec §11.1: no third-party network, HTML or image libraries) and update " +
                    "checkReleaseDependencyGroups in app/build.gradle.kts.",
            )
        }
        report.get().asFile.writeText(groups.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

/**
 * The module groups of every component the configuration [configurationName]
 * resolves to, direct and transitive. Lazy: the configuration (which the Android
 * plugin may create later) is looked up and resolved only when a task reads it.
 */
fun Project.resolvedModuleGroups(configurationName: String): Provider<Set<String>> =
    provider { configurations.getByName(configurationName) }
        .flatMap { it.incoming.resolutionResult.rootComponent }
        .map { root ->
            val seen = HashSet<ResolvedComponentResult>()
            val queue = ArrayDeque(listOf(root))
            val found = sortedSetOf<String>()
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!seen.add(component)) continue
                (component.id as? ModuleComponentIdentifier)?.let { found += it.group }
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { queue += it.selected }
            }
            found
        }
