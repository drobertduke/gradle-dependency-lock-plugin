/*
 * Copyright 2014-2015 Netflix, Inc.
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

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.ProjectSpec
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class DependencyLockPluginSpec extends ProjectSpec {
    String pluginName = 'nebula.dependency-lock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'apply plugin'() {
        when:
        project.apply plugin: pluginName

        then:
        noExceptionThrown()
    }

    def 'ensure multiple configuration lock works'() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:1.0.0'
            testCompile 'test.example:foo:1.0.1'
        }

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()

        then:
        def findFoo = { String confName ->
            project.configurations[confName].resolvedConfiguration.firstLevelModuleDependencies.find { it.moduleName == 'foo'}
        }

        findFoo('compile').moduleVersion == '1.0.0'
        findFoo('testCompile').moduleVersion == '1.0.1'
    }

    def 'read in dependencies.lock'() {
        stockTestSetup()

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '1.0.0'
    }

    // Same as 'read in dependencies.lock' except we don't call triggerAfterEvaluate
    def 'read in dependencies.lock outside an afterEvaluate block'() {
        stockTestSetup()
        project.ext.set('dependencyLock.lockAfterEvaluating', "false")

        when:
        project.apply plugin: pluginName
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '1.0.0'
    }

    def 'read in dependencies.lock ignore dependencyLock.ignore if it is not truthy'() {
        stockTestSetup()
        project.ext.set('dependencyLock.ignore', ignore)
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        expect:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == version

        where:
        ignore  || version
        false   || '1.0.0'
        'false' || '1.0.0'
        'foo'   || '1.0.0'
        0       || '1.0.0'
        'true'  || '1.0.1'
        1       || '1.0.1'
    }

    def 'ignore dependencies.lock'() {
        stockTestSetup()
        project.ext.set('dependencyLock.ignore', true)

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '1.0.1'
    }

    def 'command line file override of dependencies'() {
        stockTestSetup()
        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "test.example:foo": "2.0.1"
            }    
        '''.stripIndent()

        project.ext.set('dependencyLock.overrideFile', 'override.lock')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '2.0.1'
    }

    def 'command line override of a dependency works with no other dependency lock features'() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:1.+'
        }

        project.ext.set('dependencyLock.override', 'test.example:foo:1.0.0')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()

        then:
        def resolved = project.configurations.compile.resolvedConfiguration
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '1.0.0'
    }

    def 'command line override of a dependency with includeTransitives updates transitive versions'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
                "compile": {
                    "test.example:bar": {
                        "locked": "1.0.0",
                        "requested": "1.0.0",
                        "transitive": [ "test.example:foo" ]
                    },
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.0.0"
                    }
                }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.1.0'
        }

        project.ext.set('dependencyLock.override', 'test.example:bar:1.1.0')
        project.ext.set('dependencyLock.includeTransitives', 'true')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()

        then:
        def resolved = project.configurations.compile.resolvedConfiguration
        def bar = resolved.firstLevelModuleDependencies.find { it.moduleName == 'bar' }
        def foo = bar.getChildren().find { it.moduleName == 'foo' }
        bar.moduleVersion == '1.1.0'
        foo.moduleVersion == '1.0.1'
    }

    def 'command line override of a dependency with includeTransitives updates firstLevelTransitive versions'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
                "compile": {
                    "test.example:bar": {
                        "locked": "1.0.0",
                        "requested": "1.0.0",
                        "firstLevelTransitive": [ "test.example:foo" ]
                    },
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.0.0"
                    }
                }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.1.0'
        }

        project.ext.set('dependencyLock.override', 'test.example:bar:1.1.0')
        project.ext.set('dependencyLock.includeTransitives', 'true')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()

        then:
        def resolved = project.configurations.compile.resolvedConfiguration
        def bar = resolved.firstLevelModuleDependencies.find { it.moduleName == 'bar' }
        def foo = bar.getChildren().find { it.moduleName == 'foo' }
        bar.moduleVersion == '1.1.0'
        foo.moduleVersion == '1.0.1'
    }

    def 'command line override of a dependency'() {
        stockTestSetup()

        project.ext.set('dependencyLock.override', 'test.example:foo:2.0.1')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '2.0.1'
    }

    def 'command line override of a dependency with forces in place'() {
        stockTestSetup()

        project.dependencies {
            compile 'test.example:baz:1.+'
        }

        project.configurations.all {
            resolutionStrategy {
                force 'test.example:baz:1.0.0'
            }
        }

        project.ext.set('dependencyLock.override', 'test.example:foo:2.0.1')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '2.0.1'
        def baz = resolved.firstLevelModuleDependencies.find { it.moduleName == 'baz' }
        baz.moduleVersion == '1.0.0'
    }

    def 'command line overrides of multiple dependencies'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
                "compile": {
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    },
                    "test.example:baz": {
                        "locked": "2.0.0",
                        "requested": "2.+"
                    }
                }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:1.0.1'
            compile 'test.example:baz:2.+'
        }

        project.ext.set('dependencyLock.override', 'test.example:foo:2.0.1,test.example:baz:1.1.0')

        when:
        project.apply plugin: pluginName
        triggerAfterEvaluate()
        def resolved = project.configurations.compile.resolvedConfiguration

        then:
        def foo = resolved.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo.moduleVersion == '2.0.1'
        def baz = resolved.firstLevelModuleDependencies.find { it.moduleName == 'baz' }
        baz.moduleVersion == '1.1.0'
    }

    def 'multiproject dependencies.lock'() {
        def (Project sub1, Project sub2) = multiProjectSetup()

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerAfterEvaluate()

        then:
        def resolved1 = sub1.configurations.compile.resolvedConfiguration
        def foo1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo1.moduleVersion == '1.0.0'
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def foo2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo2.moduleVersion == '2.0.0'
    }

    def 'multiproject overrideFile'() {
        def (Project sub1, Project sub2) = multiProjectSetup()

        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "test.example:foo": "2.0.1"
            }
        '''.stripIndent()

        project.ext.set('dependencyLock.overrideFile', 'override.lock')

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerAfterEvaluate()

        then:
        def resolved1 = sub1.configurations.compile.resolvedConfiguration
        def foo1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo1.moduleVersion == '2.0.1'
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def foo2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo2.moduleVersion == '2.0.1'
    }

    def 'multiproject unused libraries in overrideFile'() {
        def (Project sub1, Project sub2) = multiProjectSetup()
        sub1.dependencies {
            compile 'test.example:bar:1.+'
        }

        sub2.dependencies {
            compile 'test.example:baz:1.+'
        }

        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "test.example:foo": "1.0.0",
              "test.example:bar": "1.0.0",
              "test.example:baz": "1.0.0"
            }
        '''.stripIndent()

        project.ext.set('dependencyLock.overrideFile', 'override.lock')

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerAfterEvaluate()

        then:
        def resolved1 = sub1.configurations.compile.resolvedConfiguration
        def foo1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo1.moduleVersion == '1.0.0'
        def bar1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'bar' }
        bar1.moduleVersion == '1.0.0'
        def baz1 = resolved1.firstLevelModuleDependencies.find { it.moduleName == 'baz' }
        baz1 == null
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def foo2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'foo' }
        foo2.moduleVersion == '1.0.0'
        def bar2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'bar' }
        bar2 == null
        def baz2 = resolved2.firstLevelModuleDependencies.find { it.moduleName == 'baz' }
        baz2.moduleVersion == '1.0.0'
    }

    def 'siblings need to lock each others dependencies'() {
        def sub1Folder = new File(projectDir, 'sub1')
        sub1Folder.mkdir()
        def sub1 = ProjectBuilder.builder().withName('sub1').withProjectDir(sub1Folder).withParent(project).build()
        def sub1DependenciesLock = new File(sub1Folder, 'dependencies.lock')
        sub1DependenciesLock << '''\
            {
                "compile": {
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    }
                }
            }
        '''.stripIndent()

        def sub2Folder = new File(projectDir, 'sub2')
        sub2Folder.mkdir()
        def sub2 = ProjectBuilder.builder().withName('sub2').withProjectDir(sub2Folder).withParent(project).build()
        def sub2DependenciesLock = new File(sub2Folder, 'dependencies.lock')
        sub2DependenciesLock << '''\
            {
                "compile": {
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "firstLevelTransitive": [ "test.nebula:sub1" ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.0",
                        "requested": "2.+",
                    },
                    "test.nebula:sub1": {
                        "project": true,
                        "firstLevelTransitive": [ "test.nebula:sub2" ]
                    }
                }
            }
        '''.stripIndent()

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
        }

        sub1.dependencies {
            compile 'test.example:baz:1.+'
        }

        sub2.dependencies {
            compile project.project(':sub1')
            compile 'test.example:foo:2.+'
        }

        when:
        project.subprojects {
            apply plugin: pluginName
        }
        triggerAfterEvaluate()

        then:
        def resolved2 = sub2.configurations.compile.resolvedConfiguration
        def baz = resolved2.resolvedArtifacts.find { it.name == 'baz' }
        baz.moduleVersion.id.version == '1.0.0'
    }

    def 'use global lock'() {
        def (Project sub1, Project sub2) = multiProjectSetup()
        new File(projectDir, 'global.lock').text = '''\
            {
                "_global_": {
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "requested": "1.+"
                    }
                }
            }
        '''.stripIndent()

        project.allprojects {
            apply plugin: 'nebula.dependency-lock'
        }

        when:
        triggerAfterEvaluate()

        then:
        def foo1 = sub1.configurations.compile.resolvedConfiguration.resolvedArtifacts.find { it.name == 'foo'}
        def foo2 = sub2.configurations.compile.resolvedConfiguration.resolvedArtifacts.find { it.name == 'foo'}
        foo1.moduleVersion.id.version == '1.0.1'
        foo2.moduleVersion.id.version == '1.0.1'
    }

    private List multiProjectSetup() {
        def sub1Folder = new File(projectDir, 'sub1')
        sub1Folder.mkdir()
        def sub1 = ProjectBuilder.builder().withName('sub1').withProjectDir(sub1Folder).withParent(project).build()
        def sub1DependenciesLock = new File(sub1Folder, 'dependencies.lock')
        sub1DependenciesLock << '''\
            {
                "compile": {
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    }
                }
            }
        '''.stripIndent()

        def sub2Folder = new File(projectDir, 'sub2')
        sub2Folder.mkdir()
        def sub2 = ProjectBuilder.builder().withName('sub2').withProjectDir(sub2Folder).withParent(project).build()
        def sub2DependenciesLock = new File(sub2Folder, 'dependencies.lock')
        sub2DependenciesLock << '''\
            {
                "compile": {
                    "test.example:foo": {
                        "locked": "2.0.0",
                        "requested": "2.+"
                    }
                }
            }
        '''.stripIndent()

        project.subprojects {
            apply plugin: 'java'
            repositories { maven { url Fixture.repo } }
        }

        sub1.dependencies {
            compile 'test.example:foo:1.+'
        }

        sub2.dependencies {
            compile 'test.example:foo:2.+'
        }

        [sub1, sub2]
    }

    private void triggerAfterEvaluate() {
        project.evaluate()
        project.subprojects.each {
            it.evaluate()
        }
    }

    private void stockTestSetup() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
                "compile": {
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    }
                },
                "default": {
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    }
                }
            }
        '''.stripIndent()

        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:1.0.1'
        }
    }
}
