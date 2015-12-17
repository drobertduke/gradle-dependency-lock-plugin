/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock

import groovy.json.JsonSlurper
import groovy.transform.TailRecursive
import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import nebula.plugin.scm.ScmPlugin
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete
import org.gradle.util.NameMatcher

import static nebula.plugin.dependencylock.tasks.GenerateLockTask.getConfigurationsFromConfigurationNames

class DependencyLockPlugin implements Plugin<Project> {
    public static final String GLOBAL_LOCK_CONFIG = '_global_'

    private static Logger logger = Logging.getLogger(DependencyLockPlugin)
    public static final String GENERATE_GLOBAL_LOCK_TASK_NAME = 'generateGlobalLock'
    public static final String UPDATE_GLOBAL_LOCK_TASK_NAME = 'updateGlobalLock'
    public static final String GENERATE_LOCK_TASK_NAME = 'generateLock'
    public static final String UPDATE_LOCK_TASK_NAME = 'updateLock'
    private static enum OVERRIDE_TYPE { SIMPLE, AFFECT_TRANSITIVES }
    private static enum OVERRIDE_PARAMETERS {
        OVERRIDE("override"),
        OVERRIDE_FILE("overrideFile"),
        OVERRIDE_AND_IGNORE_TRANSITIVE_LOCKS("overrideAndIgnoreTransitiveLocks")

        final String value
        OVERRIDE_PARAMETERS(String value) { this.value = value}
        String toString() { value }
    }

    /**
     * Finds dependencies recursively for a set of parents
     * @param Map dependencies
     * @param List parents
     * @param List children
     * @return A list of descendants for the given parents
     */
    @TailRecursive
    public static List descendants(dependencies, parents, children = []) {
        if (parents.size() == 0) {
            return children
        }
        final newChildren = parents.collectMany { parent ->
            dependencies.findAll { key, value ->
                value.transitive?.contains(parent.toString())
            }.keySet()
        }
        descendants(dependencies, newChildren, children + newChildren)
    }


    Project project

    @Override
    void apply(Project project) {
        this.project = project

        String clLockFileName = project.hasProperty('dependencyLock.lockFile') ? project['dependencyLock.lockFile'] : null
        DependencyLockExtension extension = project.extensions.create('dependencyLock', DependencyLockExtension)
        DependencyLockCommitExtension commitExtension = project.rootProject.extensions.findByType(DependencyLockCommitExtension)
        if (!commitExtension) {
            commitExtension = project.rootProject.extensions.create('commitDependencyLock', DependencyLockCommitExtension)
        }

        final Map overridesByType = loadOverrides()
        final Map overrides = combineOverrides(overridesByType)

        GenerateLockTask genLockTask = project.tasks.create(GENERATE_LOCK_TASK_NAME, GenerateLockTask)
        configureLockTask(genLockTask, clLockFileName, extension, overrides)
        if (project.hasProperty('dependencyLock.useGeneratedLock')) {
            clLockFileName = genLockTask.getDependenciesLock().path
        }

        UpdateLockTask updateLockTask = project.tasks.create(UPDATE_LOCK_TASK_NAME, UpdateLockTask)
        configureLockTask(updateLockTask, clLockFileName, extension, overrides)
        configureUpdateTask(updateLockTask, extension)

        //DiffLockTask diffLockTask = project.tasks.create('diffLock', DiffLockTask)
        //configureDiffTask(diffLockTask, genLockTask, clLockFileName)

        SaveLockTask saveTask = configureSaveTask(clLockFileName, genLockTask, updateLockTask, extension)
        createDeleteLock(saveTask)

        // configure global lock only on rootProject
        SaveLockTask globalSave
        String globalLockFileName = project.hasProperty('dependencyLock.globalLockFile') ? project['dependencyLock.globalLockFile'] : null
        GenerateLockTask globalLockTask
        UpdateLockTask globalUpdateLock
        if (project == project.rootProject) {
            globalLockTask = project.tasks.create(GENERATE_GLOBAL_LOCK_TASK_NAME, GenerateLockTask)
            if (project.hasProperty('dependencyLock.useGeneratedGlobalLock')) {
                globalLockFileName = globalLockTask.getDependenciesLock().path
            }
            configureGlobalLockTask(globalLockTask, globalLockFileName, extension, overrides)
            globalUpdateLock = project.tasks.create(UPDATE_GLOBAL_LOCK_TASK_NAME, UpdateLockTask)
            configureGlobalLockTask(globalUpdateLock, globalLockFileName, extension, overrides)
            configureUpdateTask(globalUpdateLock, extension)
            globalSave = configureGlobalSaveTask(globalLockFileName, globalLockTask, globalUpdateLock, extension)
            createDeleteGlobalLock(globalSave)
        }

        configureCommitTask(clLockFileName, globalLockFileName, saveTask, extension, commitExtension, globalSave)

        def lockAfterEvaluating = project.hasProperty('dependencyLock.lockAfterEvaluating') ? Boolean.parseBoolean(project['dependencyLock.lockAfterEvaluating']) : extension.lockAfterEvaluating
        if (lockAfterEvaluating) {
            logger.info('Applying dependency lock in afterEvaluate block')
            project.afterEvaluate { applyLockToResolutionStrategy(extension, overridesByType, globalLockFileName, clLockFileName) }
        } else {
            logger.info('Applying dependency lock as is (outside afterEvaluate block)')
            applyLockToResolutionStrategy(extension, overridesByType, globalLockFileName, clLockFileName)
        }

        project.gradle.taskGraph.whenReady { taskGraph ->
            def hasLockingTask = taskGraph.hasTask(genLockTask) || taskGraph.hasTask(updateLockTask) || ((project == project.rootProject) && (taskGraph.hasTask(globalLockTask) || taskGraph.hasTask(globalUpdateLock)))
            if (hasLockingTask) {
                project.configurations.all {
                    resolutionStrategy {
                        cacheDynamicVersionsFor 0, 'seconds'
                        cacheChangingModulesFor 0, 'seconds'
                    }
                }
                if (!shouldIgnoreDependencyLock()) {
                    applyOverrides(overrides)
                }
            }
        }
    }

    private void applyLockToResolutionStrategy(DependencyLockExtension extension, Map overridesByType, String globalLockFileName, String clLockFileName) {
        if (extension.configurationNames.empty) {
            extension.configurationNames = project.configurations.collect { it.name }
        }

        File dependenciesLock
        File globalLock = new File(project.rootProject.projectDir, globalLockFileName ?: extension.globalLockFile)
        if (globalLock.exists()) {
            dependenciesLock = globalLock
        } else {
            dependenciesLock = new File(project.projectDir, clLockFileName ?: extension.lockFile)
        }

        def taskNames = project.gradle.startParameter.taskNames

        if (dependenciesLock.exists() && !shouldIgnoreDependencyLock() && !hasGenerationTask(taskNames)) {
            applyLock(dependenciesLock, overridesByType, extension)
        } else if (!shouldIgnoreDependencyLock()) {
            applyOverrides(combineOverrides(overridesByType))
        }
    }

    private boolean hasGenerationTask(Collection<String> cliTasks) {
        def matcher = new NameMatcher()
        def found = cliTasks.find { cliTaskName ->
            def tokens = cliTaskName.split(':')
            def taskName = tokens.last()
            def generatesPresent = matcher.find(taskName, [GENERATE_LOCK_TASK_NAME, GENERATE_GLOBAL_LOCK_TASK_NAME,
                    UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME])

            generatesPresent && taskRunOnThisProject(tokens)
        }

        found != null
    }

    private boolean taskRunOnThisProject(String[] tokens) {
        if (tokens.size() == 1) { // task run globally
            return true
        } else if (tokens.size() == 2 && tokens[0] == '') { // running fully qualified on root project
            return project == project.rootProject
        } else { // the task is being run on a specific project
            return project.name == tokens[-2]
        }
    }

    private void configureCommitTask(String clLockFileName, String globalLockFileName, SaveLockTask saveTask, DependencyLockExtension lockExtension,
                                     DependencyLockCommitExtension commitExtension, SaveLockTask globalSaveTask = null) {
        project.plugins.withType(ScmPlugin) {
            if (!project.rootProject.tasks.findByName('commitLock')) {
                CommitLockTask commitTask = project.rootProject.tasks.create('commitLock', CommitLockTask)
                commitTask.mustRunAfter(saveTask)
                if (globalSaveTask) {
                    commitTask.mustRunAfter(globalSaveTask)
                }
                commitTask.conventionMapping.with {
                    scmFactory = { project.rootProject.scmFactory }
                    commitMessage = {
                        project.hasProperty('commitDependencyLock.message') ?
                                project['commitDependencyLock.message'] : commitExtension.message
                    }
                    patternsToCommit = {
                        def lockFiles = []
                        def rootLock = new File(project.rootProject.projectDir, clLockFileName ?: lockExtension.lockFile)
                        if (rootLock.exists()) {
                            lockFiles << rootLock
                        }
                        def globalLock = new File(project.rootProject.projectDir, globalLockFileName ?: lockExtension.globalLockFile)
                        if (globalLock.exists()) {
                            lockFiles << globalLock
                        }
                        project.rootProject.subprojects.each {
                            def potentialLock = new File(it.projectDir, clLockFileName ?: lockExtension.lockFile)
                            if (potentialLock.exists()) {
                                lockFiles << potentialLock
                            }
                        }
                        def patterns = lockFiles.collect {
                            project.rootProject.projectDir.toURI().relativize(it.toURI()).path
                        }
                        logger.info(patterns.toString())
                        patterns
                    }
                    shouldCreateTag = {
                        project.hasProperty('commitDependencyLock.tag') ?: commitExtension.shouldCreateTag
                    }
                    tag = {
                        project.hasProperty('commitDependencyLock.tag') ? project['commitDependencyLock.tag'] : commitExtension.tag.call()
                    }
                    remoteRetries = { commitExtension.remoteRetries }
                }
            }
        }
    }

    private SaveLockTask configureSaveTask(String lockFileName, GenerateLockTask lockTask, UpdateLockTask updateTask, DependencyLockExtension extension) {
        SaveLockTask saveTask = project.tasks.create('saveLock', SaveLockTask)
        saveTask.doFirst {
            SaveLockTask globalSave = project.rootProject.tasks.findByName('saveGlobalLock')
            if (globalSave?.outputLock?.exists()) {
                throw new GradleException('Cannot save individual locks when global lock is in place, run deleteGlobalLock task')
            }
        }
        saveTask.conventionMapping.with {
            generatedLock = { lockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, lockFileName ?: extension.lockFile) }
        }
        configureCommonSaveTask(saveTask, lockTask, updateTask)

        saveTask
    }

    private void configureCommonSaveTask(SaveLockTask saveTask, GenerateLockTask lockTask, UpdateLockTask updateTask) {
        saveTask.mustRunAfter lockTask, updateTask
        saveTask.outputs.upToDateWhen {
            if (saveTask.generatedLock.exists() && saveTask.outputLock.exists()) {
                saveTask.generatedLock.text == saveTask.outputLock.text
            } else {
                false
            }
        }
    }

    private SaveLockTask configureGlobalSaveTask(String globalLockFileName, GenerateLockTask globalLockTask, UpdateLockTask globalUpdateLockTask, DependencyLockExtension extension) {
        SaveLockTask globalSaveTask = project.tasks.create('saveGlobalLock', SaveLockTask)
        globalSaveTask.doFirst {
            project.subprojects.each { Project sub ->
                SaveLockTask save = sub.tasks.findByName('saveLock')
                if (save && save.outputLock?.exists()) {
                    throw new GradleException('Cannot save global lock, one or more individual locks are in place, run deleteLock task')
                }
            }
        }
        globalSaveTask.conventionMapping.with {
            generatedLock = { globalLockTask.dependenciesLock }
            outputLock = { new File(project.projectDir, globalLockFileName ?: extension.globalLockFile) }
        }
        configureCommonSaveTask(globalSaveTask, globalLockTask, globalUpdateLockTask)

        globalSaveTask
    }

    private GenerateLockTask configureLockTask(GenerateLockTask lockTask, String clLockFileName, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(lockTask, extension, overrides)
        lockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, clLockFileName ?: extension.lockFile)
            }
            configurationNames = { extension.configurationNames }
        }

        lockTask
    }

    private Boolean shouldIncludeTransitives(DependencyLockExtension extension) {
        project.hasProperty('dependencyLock.includeTransitives') ?
                Boolean.parseBoolean(project['dependencyLock.includeTransitives']) : extension.includeTransitives
    }

    private void setupLockConventionMapping(GenerateLockTask task, DependencyLockExtension extension, Map overrideMap) {
        task.conventionMapping.with {
            skippedDependencies = { extension.skippedDependencies }
            includeTransitives = { shouldIncludeTransitives(extension) }
            filter = { extension.dependencyFilter }
            overrides = { overrideMap }
        }
    }

    private GenerateLockTask configureGlobalLockTask(GenerateLockTask globalLockTask, String globalLockFileName, DependencyLockExtension extension, Map overrides) {
        setupLockConventionMapping(globalLockTask, extension, overrides)
        globalLockTask.doFirst {
            project.subprojects.each { sub -> sub.repositories.each { repo -> project.repositories.add(repo) } }
        }
        globalLockTask.conventionMapping.with {
            dependenciesLock = {
                new File(project.buildDir, globalLockFileName ?: extension.globalLockFile)
            }
            configurations = {
                def subprojects = project.subprojects.collect { subproject ->
                    def ext = subproject.getExtensions().findByType(DependencyLockExtension)
                    if (ext != null) {
                        ext.configurationNames.collect { subconf ->
                            project.dependencies.create(project.dependencies.project(path: subproject.path, configuration: subconf))
                        }
                    } else {
                        [project.dependencies.create(subproject)]
                    }
                }.flatten()
                def subprojectsArray = subprojects.toArray(new Dependency[subprojects.size()])
                def conf = project.configurations.detachedConfiguration(subprojectsArray)
                project.allprojects.each { it.configurations.add(conf) }

                [conf] + getConfigurationsFromConfigurationNames(project, extension.configurationNames)
            }
        }

        globalLockTask
    }

    private void createDeleteLock(SaveLockTask saveLock) {
        project.tasks.create('deleteLock', Delete) {
            delete saveLock.outputLock
        }
    }

    private void createDeleteGlobalLock(SaveLockTask saveGlobalLock) {
        project.tasks.create('deleteGlobalLock', Delete) {
            delete saveGlobalLock.outputLock
        }
    }

    private configureUpdateTask(UpdateLockTask lockTask, DependencyLockExtension extension) {
        // You can't read a property at the same time you define the convention mapping ∞
        def updatesFromOption = lockTask.dependencies
        lockTask.conventionMapping.dependencies = { updatesFromOption ?: extension.updateDependencies }

        lockTask
    }

    /*private configureDiffTask(DiffLockTask diffLockTask, GenerateLockTask generateLockTask, String lockFileName, DependencyLockExtension extension) {
        diffLockTask.conventionMapping.with {
            existingLock = { new File(project.projectDir, lockFileName ?: extension.lockFile) }
        }

        diffLockTask.newLock = generateLockTask.dependenciesLock
        diffLockTask.output = project.file('build/reports/dependencylock/lockdiff.txt')
    }*/

    void applyOverrides(Map overrides) {
        if (project.hasProperty('dependencyLock.overrideFile')) {
            logger.info("Using override file ${project['dependencyLock.overrideFile']} to lock dependencies")
        }
        if (project.hasProperty('dependencyLock.override')) {
            logger.info("Using command line overrides ${project['dependencyLock.override']}")
        }

        def overrideForces = overrides.collect { "${it.key}:${it.value}" }
        logger.debug(overrideForces.toString())

        project.configurations.all {
            resolutionStrategy {
                overrideForces.each { dep -> force dep }
            }
        }
    }

    void applyLock(File dependenciesLock, Map overridesByType, DependencyLockExtension extension) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies")
        def locks = loadLock(dependenciesLock)

        warnIfDeprecated(locks, dependenciesLock.name)

        project.configurations.all({ Configuration conf ->
            // In the old format of the lock file, there was only one locked setting. In that case, apply it on all configurations.
            // In the new format, apply _global_ to all configurations or use the config name
            def deps = isDeprecatedFormat(locks) ? locks : locks[GLOBAL_LOCK_CONFIG] ?: locks[conf.name]
            if (deps) {
                // Non-project locks are the top-level dependencies, and possibly transitive thereof, of this project which are
                // locked by the lock file. There may also be dependencies on other projects. These are not captured here.
                def nonProjectLocks = deps.findAll { it.value?.locked }

                // If overrideAndIgnoreTransitiveLocks is used, we exclude any locks on transitives of the overridden
                // packages.
                final transitivesToIgnore = descendants(deps, overridesByType[OVERRIDE_TYPE.AFFECT_TRANSITIVES].keySet())

                final locksFromFile = nonProjectLocks.collectEntries { [it.key, it.value.locked] }
                final fileLocksRespected = locksFromFile.findAll { !transitivesToIgnore.contains(it.key) }

                final overrides = combineOverrides(overridesByType)
                final locksWithOverrides = fileLocksRespected + overrides

                logger.debug('lockForces: {}', locksWithOverrides)

                resolutionStrategy {
                    force locksWithOverrides.collect { key, value -> "${key}:${value}" }
                }
            }
        })
    }

    boolean shouldIgnoreDependencyLock() {
        if (project.hasProperty('dependencyLock.ignore')) {
            def prop = project.property('dependencyLock.ignore')

            (prop instanceof String) ? prop.toBoolean() : prop.asBoolean()
        } else {
            false
        }
    }

    private void warnIfDeprecated(final Object lockFileContents, final String lockFileName) {
        if (isDeprecatedFormat(lockFileContents)) {
            logger.warn("The override file ${lockFileName} is using a deprecated format. Support for this format may be removed in future versions.")
        }
    }

    private Boolean isDeprecatedFormat(final Object lockFileContents) {
        // Which method is correct?
        //lockFileContents.every { it.key ==~ /[^:]+:.+/ } // in the old format, all first level props were groupId:artifactId
        lockFileContents.any { it.value.getClass() != String && it.value.locked }
    }

    /**
     * Parses any command line overrides and returns them with the override type.
     * @return A map of override type to the package/version overrides of that type.
     */
    private Map loadOverrides() {
        // Overrides are dependencies that trump the lock file.
        if (shouldIgnoreDependencyLock()) {
            return [(OVERRIDE_TYPE.AFFECT_TRANSITIVES): [:], (OVERRIDE_TYPE.SIMPLE): [:]]
        }
        final Map overridesByParameter = parseOverrideParameters()
        // Merges the two maps by starting with any overrides from 'overrideFile' and adding overrides from 'override'.
        // Command line 'override' wins in the case of conflicts.
        final Map simpleOverrides = overridesByParameter[OVERRIDE_PARAMETERS.OVERRIDE_FILE] +
                overridesByParameter[OVERRIDE_PARAMETERS.OVERRIDE]
        final Map overridesByType = [
                (OVERRIDE_TYPE.AFFECT_TRANSITIVES):
                        overridesByParameter[OVERRIDE_PARAMETERS.OVERRIDE_AND_IGNORE_TRANSITIVE_LOCKS],
                (OVERRIDE_TYPE.SIMPLE): simpleOverrides
        ]
        logger.debug "Overrides loaded: ${overridesByType}"
        return overridesByType
    }

    /**
     * Takes a string of comma delimited overrides from the command line and parses them.
     * @param overrideTargets
     * @return A map of package to version (or null)
     */
    private Map parseOverrideTargets(final String overrideTargets) {
        overrideTargets?.tokenize(',')?.collectEntries {
            def (group, artifact, version) = it.tokenize(':')
            ["${group}:${artifact}", version]
        }
    }

    /**
     * Parses the three types of command line overrides parameters
     * @return A map of the override parameter type to the package/version overrides from that parameter.
     */
    private Map parseOverrideParameters() {
        OVERRIDE_PARAMETERS.values().collectEntries { parameterType ->
            final String parameterName = "dependencyLock.${parameterType}"
            def value = [:]
            if (project.hasProperty(parameterName)) {
                final String argument = project[parameterName]
                if (!argument.is(null)) {
                    switch(parameterType) {
                        case OVERRIDE_PARAMETERS.OVERRIDE:
                            value = parseOverrideTargets(argument)
                            break
                        case OVERRIDE_PARAMETERS.OVERRIDE_AND_IGNORE_TRANSITIVE_LOCKS:
                            value = parseOverrideTargets(argument)
                            break
                        case OVERRIDE_PARAMETERS.OVERRIDE_FILE:
                            final overrideFile = new File(project.rootDir, argument)
                            final locks = loadLock(overrideFile)
                            warnIfDeprecated(locks, overrideFile.name)
                            locks.each {
                                value[it.key] = isDeprecatedFormat(locks) ? it.value.locked : it.value
                            }
                            break
                    }
                }
            }
            [parameterType, value]
        }
    }

    /**
     * Takes a map of overrides by type and flattens it, discarding the override type information.
     * @param overridesByType
     * @return A map of package to version
     */
    private Map combineOverrides(final Map overridesByType) {
        overridesByType[OVERRIDE_TYPE.SIMPLE] + overridesByType[OVERRIDE_TYPE.AFFECT_TRANSITIVES]
    }

    private loadLock(File lock) {
        try {
            return new JsonSlurper().parseText(lock.text)
        } catch (ex) {
            logger.debug('Unreadable json file: ' + lock.text)
            logger.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
    }
}
