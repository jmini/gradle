/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testing.testsuites

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Ignore


class TestSuitesDependenciesIntegrationTest extends AbstractIntegrationSpec {
    private versionCatalog = file('gradle', 'libs.versions.toml')

    // region basic functionality
    def 'suites do not share dependencies by default'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                test {
                    dependencies {
                        implementation 'org.apache.commons:commons-lang3:3.11'
                    }
                }
                integTest(JvmTestSuite) {
                    useJUnit()
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testCompileClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 is an implementation dependency for the default test suite'
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 is an implementation dependency for the default test suite'
                assert !configurations.integTestCompileClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'default test suite dependencies should not leak to integTest'
                assert !configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'default test suite dependencies should not leak to integTest'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def "suites support annotationProcessor dependencies"() {
        given: "a suite that uses Google's Auto Value as an example of an annotation processor"
        settingsFile << """rootProject.name = 'Test'"""
        buildFile << """plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation 'com.google.auto.value:auto-value-annotations:1.9'
                            annotationProcessor 'com.google.auto.value:auto-value:1.9'
                        }
                    }
                }
            }
            """.stripIndent()

        file("src/test/java/Animal.java") << """
            import com.google.auto.value.AutoValue;

            @AutoValue
            abstract class Animal {
              static Animal create(String name, int numberOfLegs) {
                return new AutoValue_Animal(name, numberOfLegs);
              }

              abstract String name();
              abstract int numberOfLegs();
            }
            """.stripIndent()

        file("src/test/java/AnimalTest.java") << """
            import org.junit.Test;

            import static org.junit.Assert.assertEquals;

            public class AnimalTest {
                @Test
                public void testCreateAnimal() {
                    Animal dog = Animal.create("dog", 4);
                    assertEquals("dog", dog.name());
                    assertEquals(4, dog.numberOfLegs());
                }
            }
            """.stripIndent()

        expect: "tests using a class created by running that annotation processor will succeed"
        succeeds('test')
    }
    // endregion basic functionality

    // region dependencies - projects
    def 'default suite has project dependency by default; others do not'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                integTest(JvmTestSuite)
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert !configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'integTest does not implicitly depend on the production project'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'custom suites have project dependency if explicitly set'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation project
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testCompileClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'integTest explicitly depends on the production project'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependencies to other projects to suites'() {
        given:
        multiProjectBuild('root', ['consumer', 'util']) {
            buildFile << """
                subprojects { apply plugin: 'java-library'}
                project(':util') {
                    dependencies { api 'org.apache.commons:commons-lang3:3.11' }
                }
            """
        }

        file('consumer/build.gradle') << """
            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        dependencies {
                            implementation project(':util')
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                doLast {
                    assert configurations.testCompileClasspath.files*.name.contains('commons-lang3-3.11.jar')
                }
            }
        """

        expect:
        succeeds ':consumer:checkConfiguration'
    }

    def 'can add dependencies to other projects to suites with actions '() {
        given:
        multiProjectBuild('root', ['consumer', 'util']) {
            buildFile << """
                subprojects { apply plugin: 'java-library'}
                project(':util') {
                    dependencies { api 'org.apache.commons:commons-lang3:3.11' }
                }
            """
        }

        file('consumer/build.gradle') << """
            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        dependencies {
                            implementation(project(':util')) {
                                exclude group: 'org.apache.commons', module: 'commons-lang3'
                            }
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                doLast {
                    assert !configurations.testCompileClasspath.files*.name.contains('commons-lang3-3.11.jar')
                }
            }
        """

        expect:
        succeeds ':consumer:checkConfiguration'
    }

    // endregion dependencies - projects

    // region dependencies - modules (GAV)
    def 'can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite using a #desc'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                test {
                    dependencies {
                        implementation $implementationNotationTest
                        compileOnly $compileOnlyNotationTest
                        runtimeOnly $runtimeOnlyNotationTest
                    }
                }
                integTest(JvmTestSuite) {
                    // intentionally setting lower versions of the same dependencies on the `test` suite to show that no conflict resolution should be taking place
                    dependencies {
                        implementation project
                        implementation $implementationNotationInteg
                        compileOnly $compileOnlyNotationInteg
                        runtimeOnly $runtimeOnlyNotationInteg
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar', 'guava-30.1.1-jre.jar')
                assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-30.1.1-jre.jar', 'mysql-connector-java-8.0.26.jar')
                assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'

                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('servlet-api-2.5.jar', 'guava-29.0-jre.jar')
                assert !integTestCompileClasspathFileNames.contains('commons-lang3-3.11.jar') : 'implementation dependency of project, should not leak to integTest'
                assert !integTestCompileClasspathFileNames.contains('mysql-connector-java-6.0.6.jar'): 'runtimeOnly dependency'
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-29.0-jre.jar', 'mysql-connector-java-6.0.6.jar')
                assert !integTestRuntimeClasspathFileNames.contains('servlet-api-2.5.jar'): 'compileOnly dependency'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'

        where:
        desc                | implementationNotationTest                  | compileOnlyNotationTest                           | runtimeOnlyNotationTest                     | implementationNotationInteg                  | compileOnlyNotationInteg                           | runtimeOnlyNotationInteg
        'GAV string'        | gavStr(guavaGroup, guavaName, guavaVerTest) | gavStr(servletGroup, servletName, servletVerTest) | gavStr(mysqlGroup, mysqlName, mysqlVerTest) | gavStr(guavaGroup, guavaName, guavaVerInteg) | gavStr(servletGroup, servletName, servletVerInteg) | gavStr(mysqlGroup, mysqlName, mysqlVerInteg)
        'GAV map'           | gavMap(guavaGroup, guavaName, guavaVerTest) | gavMap(servletGroup, servletName, servletVerTest) | gavMap(mysqlGroup, mysqlName, mysqlVerTest) | gavMap(guavaGroup, guavaName, guavaVerInteg) | gavMap(servletGroup, servletName, servletVerInteg) | gavMap(mysqlGroup, mysqlName, mysqlVerInteg)
    }

    def 'can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite via DependencyHandler using a #desc'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite)
            }
        }

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'

            testImplementation $implementationNotationTest
            testCompileOnly $compileOnlyNotationTest
            testRuntimeOnly $runtimeOnlyNotationTest

            // intentionally setting lower versions of the same dependencies on the `test` suite to show that no conflict resolution should be taking place
            integTestImplementation project
            integTestImplementation $implementationNotationInteg
            integTestCompileOnly  $compileOnlyNotationInteg
            integTestRuntimeOnly $runtimeOnlyNotationInteg
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar', 'guava-30.1.1-jre.jar')
                assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-30.1.1-jre.jar', 'mysql-connector-java-8.0.26.jar')
                assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'

                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('servlet-api-2.5.jar', 'guava-29.0-jre.jar')
                assert !integTestCompileClasspathFileNames.contains('commons-lang3-3.11.jar') : 'implementation dependency of project, should not leak to integTest'
                assert !integTestCompileClasspathFileNames.contains('mysql-connector-java-6.0.6.jar'): 'runtimeOnly dependency'
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-29.0-jre.jar', 'mysql-connector-java-6.0.6.jar')
                assert !integTestRuntimeClasspathFileNames.contains('servlet-api-2.5.jar'): 'compileOnly dependency'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'

        where:
        desc                | implementationNotationTest                  | compileOnlyNotationTest                           | runtimeOnlyNotationTest                     | implementationNotationInteg                  | compileOnlyNotationInteg                           | runtimeOnlyNotationInteg
        'GAV string'        | gavStr(guavaGroup, guavaName, guavaVerTest) | gavStr(servletGroup, servletName, servletVerTest) | gavStr(mysqlGroup, mysqlName, mysqlVerTest) | gavStr(guavaGroup, guavaName, guavaVerInteg) | gavStr(servletGroup, servletName, servletVerInteg) | gavStr(mysqlGroup, mysqlName, mysqlVerInteg)
        'GAV map'           | gavMap(guavaGroup, guavaName, guavaVerTest) | gavMap(servletGroup, servletName, servletVerTest) | gavMap(mysqlGroup, mysqlName, mysqlVerTest) | gavMap(guavaGroup, guavaName, guavaVerInteg) | gavMap(servletGroup, servletName, servletVerInteg) | gavMap(mysqlGroup, mysqlName, mysqlVerInteg)
    }

    def "can add dependency with actions on suite using a #desc"() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                test {
                    dependencies {
                        implementation($dependencyNotation) {
                            exclude group: '$collectionsGroup', module: '$collectionsName'
                        }
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                assert testCompileClasspathFileNames.containsAll('${beanUtilsName}-${beanUtilsVer}.jar')
                assert !testCompileClasspathFileNames.contains('${collectionsName}-${collectionsVer}.jar'): 'excluded dependency'
            }
        }
        """

        file('src/main/org/sample/Person.java') << """
            package org.sample;

            public class Person {
                private String name;
                private int age;

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public int getAge() {
                    return age;
                }

                public void setAge(int age) {
                    this.age = age;
                }
            }
        """

        file('src/test/org/samplePersonTest.java') << """
            package org.sample;

            import org.apache.commons.beanutils.PropertyUtils;

            public class PersonTest {
                @Test
                public void testPerson() {
                    Object person = new Person();
                    PropertyUtils.setSimpleProperty(person, "name", "Bart Simpson");
                    PropertyUtils.setSimpleProperty(person, "age", 38);
                    assertEquals("Bart Simpson", person.getName());
                    assertEquals(38, person.getAge());
                }
            }
        """

        expect:
        succeeds 'checkConfiguration'

        where:
        desc                | dependencyNotation
        'GAV string'        | gavStr(beanUtilsGroup, beanUtilsName, beanUtilsVer)
        'GAV map'           | gavMap(beanUtilsGroup, beanUtilsName, beanUtilsVer)
    }

    def "can add dependencies using a non-String CharSequence: #type"() {
        given:
        buildFile << """
        import org.apache.commons.lang3.text.StrBuilder;

        buildscript {
            ${mavenCentralRepository()}

            dependencies {
                classpath("org.apache.commons:commons-lang3:3.11")
            }
        }

        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        $type buf = $creationNotation

        testing {
            suites {
                test {
                    dependencies {
                        implementation(buf)
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar')
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'

        where:
        type            | creationNotation
        'StringBuffer'  | "new StringBuffer('org.apache.commons:commons-lang3:3.11')"
        'StringBuilder' | "new StringBuilder('org.apache.commons:commons-lang3:3.11')"
        'StrBuilder'    | "new StrBuilder('org.apache.commons:commons-lang3:3.11')"
        'GString'       | '"org.apache.commons:commons-lang3:3.${(11 + 11) / (2 + 1 - 1)}"'
    }

    private static guavaGroup = 'com.google.guava'
    private static guavaName = 'guava'
    private static guavaVerTest = '30.1.1-jre'
    private static guavaVerInteg = '29.0-jre'

    private static servletGroup = 'javax.servlet'
    private static servletName = 'servlet-api'
    private static servletVerTest = '3.0-alpha-1'
    private static servletVerInteg = '2.5'

    private static mysqlGroup = 'mysql'
    private static mysqlName = 'mysql-connector-java'
    private static mysqlVerTest = '8.0.26'
    private static mysqlVerInteg = '6.0.6'

    private static beanUtilsGroup = 'commons-beanutils'
    private static beanUtilsName = 'commons-beanutils'
    private static beanUtilsVer = '1.9.4'

    private static collectionsGroup = 'commons-collections'
    private static collectionsName = 'commons-collections'
    private static collectionsVer = '3.2.2'

    private static gavStr(String group, String name, String version) {
        return "'$group:$name:$version'"
    }

    private static gavMap(String group, String name, String version) {
        return "group: '$group', name: '$name', version: '$version'"
    }
    // endregion dependencies - modules (GAV)

    // region dependencies - dependency objects
    def 'can add dependency objects to the implementation, compileOnly and runtimeOnly configurations of a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def commonsLang = dependencies.create 'org.apache.commons:commons-lang3:3.11'
            def servletApi = dependencies.create 'javax.servlet:servlet-api:3.0-alpha-1'
            def mysql = dependencies.create 'mysql:mysql-connector-java:8.0.26'

            testing {
                suites {
                    test {
                        dependencies {
                            implementation commonsLang
                            compileOnly servletApi
                            runtimeOnly mysql
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                    def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                    assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar')
                    assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                    assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'mysql-connector-java-8.0.26.jar')
                    assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'
                }
            }
             """

            expect:
            succeeds 'checkConfiguration'
        }

    def 'can add dependency objects with actions to a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def beanUtils = dependencies.create 'commons-beanutils:commons-beanutils:1.9.4'

            testing {
                suites {
                    test {
                        dependencies {
                            implementation(beanUtils) {
                                exclude group: 'commons-collections', module: 'commons-collections'
                            }
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name

                    assert testCompileClasspathFileNames.contains('commons-beanutils-1.9.4.jar')
                    assert !testCompileClasspathFileNames.contains('commons-collections-3.2.2.jar'): 'excluded dependency'
                }
            }
             """

        expect:
        succeeds 'checkConfiguration'
    }
    // endregion dependencies - dependency objects

    // region dependencies - dependency providers
    def 'can add dependency providers which provide dependency objects to the implementation, compileOnly and runtimeOnly configurations of a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def commonsLang = project.provider(() -> dependencies.create 'org.apache.commons:commons-lang3:3.11')
            def servletApi = project.provider(() -> dependencies.create 'javax.servlet:servlet-api:3.0-alpha-1')
            def mysql = project.provider(() -> dependencies.create 'mysql:mysql-connector-java:8.0.26')

            testing {
                suites {
                    test {
                        dependencies {
                            implementation commonsLang
                            compileOnly servletApi
                            runtimeOnly mysql
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                    def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                    assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar')
                    assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                    assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'mysql-connector-java-8.0.26.jar')
                    assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'
                }
            }
             """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependency providers which provide GAVs to the implementation, compileOnly and runtimeOnly configurations of a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def commonsLang = project.provider(() -> 'org.apache.commons:commons-lang3:3.11')
            def servletApi = project.provider(() -> 'javax.servlet:servlet-api:3.0-alpha-1')
            def mysql = project.provider(() -> 'mysql:mysql-connector-java:8.0.26')

            testing {
                suites {
                    test {
                        dependencies {
                            implementation commonsLang
                            compileOnly servletApi
                            runtimeOnly mysql
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                    def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                    assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar')
                    assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                    assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'mysql-connector-java-8.0.26.jar')
                    assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'
                }
            }
             """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependency providers which provide dependency objects with actions to a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def beanUtils = project.provider(() -> dependencies.create 'commons-beanutils:commons-beanutils:1.9.4')

            testing {
                suites {
                    test {
                        dependencies {
                            implementation(beanUtils) {
                                exclude group: 'commons-collections', module: 'commons-collections'
                            }
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name

                    assert testCompileClasspathFileNames.contains('commons-beanutils-1.9.4.jar')
                    assert !testCompileClasspathFileNames.contains('commons-collections-3.2.2.jar'): 'excluded dependency'
                }
            }
             """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependency providers which provide GAVs with actions to a suite'() {
        given :
        buildFile << """
            plugins {
              id 'java-library'
            }

            ${mavenCentralRepository()}

            def beanUtils = project.provider(() -> 'commons-beanutils:commons-beanutils:1.9.4')

            testing {
                suites {
                    test {
                        dependencies {
                            implementation(beanUtils) {
                                exclude group: 'commons-collections', module: 'commons-collections'
                            }
                        }
                    }
                }
            }

            tasks.register('checkConfiguration') {
                dependsOn test
                doLast {
                    def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name

                    assert testCompileClasspathFileNames.contains('commons-beanutils-1.9.4.jar')
                    assert !testCompileClasspathFileNames.contains('commons-collections-3.2.2.jar'): 'excluded dependency'
                }
            }
             """

        expect:
        succeeds 'checkConfiguration'
    }
    // endregion dependencies - dependency providers

    // region dependencies - Version Catalog
    def 'can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite via a Version Catalog'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.guava
                        compileOnly libs.commons.lang3
                        runtimeOnly libs.mysql.connector
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestCompileClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        guava = "30.1.1-jre"
        commons-lang3 = "3.11"
        mysql-connector = "6.0.6"

        [libraries]
        guava = { module = "com.google.guava:guava", version.ref = "guava" }
        commons-lang3 = { module = "org.apache.commons:commons-lang3", version.ref = "commons-lang3" }
        mysql-connector = { module = "mysql:mysql-connector-java", version.ref = "mysql-connector" }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependencies via a Version Catalog with actions'() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                test {
                    dependencies {
                        implementation(libs.commons.beanutils) {
                            exclude group: 'commons-collections', module: 'commons-collections'
                        }
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name

                assert testCompileClasspathFileNames.contains('commons-beanutils-1.9.4.jar')
                assert !testCompileClasspathFileNames.contains('commons-collections-3.2.2.jar'): 'excluded dependency'
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        commons-beanutils = "1.9.4"

        [libraries]
        commons-beanutils = { module = "commons-beanutils:commons-beanutils", version.ref = "commons-beanutils" }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependencies using a Version Catalog bundle to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.bundles.groovy
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('groovy-json-3.0.5.jar', 'groovy-nio-3.0.5.jar', 'groovy-3.0.5.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('groovy-json-3.0.5.jar', 'groovy-nio-3.0.5.jar', 'groovy-3.0.5.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        groovy = "3.0.5"

        [libraries]
        groovy-core = { module = "org.codehaus.groovy:groovy", version.ref = "groovy" }
        groovy-json = { module = "org.codehaus.groovy:groovy-json", version.ref = "groovy" }
        groovy-nio = { module = "org.codehaus.groovy:groovy-nio", version.ref = "groovy" }
        commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = { strictly = "[3.8, 4.0[", prefer="3.9" } }

        [bundles]
        groovy = ["groovy-core", "groovy-json", "groovy-nio"]
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependencies using a Version Catalog with a hierarchy of aliases to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.commons
                        implementation libs.commons.collections
                        runtimeOnly libs.commons.io
                        runtimeOnly libs.commons.io.csv
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.12.0.jar', 'commons-collections4-4.4.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.12.0.jar', 'commons-collections4-4.4.jar', 'commons-io-2.11.0.jar', 'commons-csv-1.9.0.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        commons-lang = "3.12.0"
        commons-collections = "4.4"
        commons-io = "2.11.0"
        commons-io-csv = "1.9.0"

        [libraries]
        commons = { group = "org.apache.commons", name = "commons-lang3", version.ref = "commons-lang" }
        commons-collections = { group = "org.apache.commons", name = "commons-collections4", version.ref = "commons-collections" }
        commons-io = { group = "commons-io", name = "commons-io", version.ref = "commons-io" }
        commons-io-csv = { group = "org.apache.commons", name = "commons-csv", version.ref = "commons-io-csv" }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'can add dependencies using a Version Catalog defined programmatically to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.guava
                        compileOnly libs.commons.lang3
                        runtimeOnly libs.mysql.connector
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestCompileClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
            }
        }
        """

        settingsFile << """
        dependencyResolutionManagement {
            versionCatalogs {
                libs {
                    version('guava', '30.1.1-jre')
                    version('commons-lang3', '3.11')
                    version('mysql-connector', '6.0.6')

                    library('guava', 'com.google.guava', 'guava').versionRef('guava')
                    library('commons-lang3', 'org.apache.commons', 'commons-lang3').versionRef('commons-lang3')
                    library('mysql-connector', 'mysql', 'mysql-connector-java').versionRef('mysql-connector')
                }
            }
        }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }
    // endregion dependencies - Version Catalog

    // region dependencies - platforms
    def "suites support platforms"() {
        given: "a suite that uses a platform dependency"
        settingsFile << """rootProject.name = 'Test'

            include 'platform', 'consumer'""".stripIndent()
        file('platform/build.gradle') << """plugins {
                id 'java-platform'
            }

            dependencies {
                constraints {
                    api 'org.apache.commons:commons-lang3:3.8.1'
                }
            }
            """.stripIndent()

        file('consumer/build.gradle') << """plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation project.dependencies.platform(project(':platform'))
                            implementation 'org.apache.commons:commons-lang3'
                        }
                    }
                }
            }
            """.stripIndent()

        file("consumer/src/test/java/SampleTest.java") << """
            import org.apache.commons.lang3.StringUtils;
            import org.junit.Test;

            import static org.junit.Assert.assertTrue;

            public class SampleTest {
                @Test
                public void testCommons() {
                    assertTrue(StringUtils.isAllLowerCase("abc"));
                }
            }
            """.stripIndent()

        expect: "tests using a class from that platform will succeed"
        succeeds('test')
    }
    // endregion dependencies - platforms

    // region dependencies - file collections
    def "can add file collection dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite"() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        testing {
            suites {
                test {
                    dependencies {
                        implementation files('libs/dummy-1.jar')
                        compileOnly files('libs/dummy-2.jar')
                        runtimeOnly files('libs/dummy-3.jar')
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                assert testCompileClasspathFileNames.containsAll('dummy-1.jar')
                assert testRuntimeClasspathFileNames.containsAll('dummy-1.jar')
                assert testCompileClasspathFileNames.containsAll('dummy-2.jar')
                assert !testRuntimeClasspathFileNames.containsAll('dummy-2.jar')
                assert !testCompileClasspathFileNames.containsAll('dummy-3.jar')
                assert testRuntimeClasspathFileNames.containsAll('dummy-3.jar')
            }
        }
        """

        file('libs/dummy-1.jar').createFile()
        file('libs/dummy-2.jar').createFile()
        file('libs/dummy-3.jar').createFile()

        expect:
        succeeds 'checkConfiguration'
    }

    def "can add file collection dependencies to a suite using fileTree"() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        testing {
            suites {
                test {
                    dependencies {
                        implementation fileTree('libs') {
                            include 'dummy-*.jar'
                        }
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                assert testCompileClasspathFileNames.containsAll('dummy-1.jar', 'dummy-2.jar', 'dummy-3.jar')
            }
        }
        """

        file('libs/dummy-1.jar').createFile()
        file('libs/dummy-2.jar').createFile()
        file('libs/dummy-3.jar').createFile()

        expect:
        succeeds 'checkConfiguration'
    }

    def "can add file collection dependencies to suites with actions"() {
        given:
        buildFile << """
        plugins {
          id 'java-library'
        }

        List configurationActions = []

        testing {
            suites {
                test {
                    dependencies {
                        implementation(files('libs/dummy-1.jar', 'libs/dummy-2.jar')) {
                            configurationActions << 'configured files'
                        }
                    }
                }
            }
        }

        tasks.register('checkConfiguration') {
            dependsOn test
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                assert testCompileClasspathFileNames.containsAll('dummy-1.jar', 'dummy-2.jar')

                assert configurationActions.containsAll('configured files')
            }
        }
        """

        file('libs/dummy-1.jar').createFile()
        file('libs/dummy-2.jar').createFile()
        file('libs/dummy-3.jar').createFile()

        expect:
        succeeds 'checkConfiguration'
    }
    // endregion dependencies - file collections

    // region dependencies - self-resolving dependencies
    @Ignore("self-resolving methods not yet available in test suites")
    def "can add localGroovy dependency to the default suite"() {
        given:
        buildFile << """
            plugins {
                id 'groovy'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation localGroovy()
                        }
                    }
                }
            }
        """

        file("src/test/groovy/Tester.groovy") << """
            import org.junit.Test

            class Tester {
                @Test
                public void testGroovyListOperations() {
                    List myList = ['Jack']
                    myList << 'Jill'
                }
            }
        """

        expect:
        succeeds('test')
    }

    @Ignore("self-resolving methods not yet available in test suites")
    def "can add localGroovy dependency to a custom suite"() {
        given:
        buildFile << """
            plugins {
                id 'groovy'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    integTest(JvmTestSuite) {
                        useJUnit()
                        dependencies {
                            implementation localGroovy()
                        }
                    }
                }
            }
        """

        file("src/integTest/groovy/Tester.groovy") << """
            import org.junit.Test

            class Tester {
                @Test
                public void testGroovyListOperations() {
                    List myList = ['Jack']
                    myList << 'Jill'
                }
            }
        """

        expect:
        succeeds('integTest')
    }

    @Ignore("self-resolving methods not yet available in test suites")
    def "can add gradleApi dependency to default suite"() {
        given:
        buildFile << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation gradleApi()
                        }
                    }
                }
            }
        """

        file("src/test/java/Tester.java") << """
            import org.junit.Test;
            import org.gradle.api.file.FileType;

            public class Tester {
                @Test
                public void testGradleApiAvailability() {
                    FileType type = FileType.FILE;
                }
            }
        """

        expect:
        succeeds('test')
    }

    @Ignore("self-resolving methods not yet available in test suites")
    def "can add gradleApi dependency to a custom suite"() {
        given:
        buildFile << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    integTest(JvmTestSuite) {
                        useJUnit()
                        dependencies {
                            implementation gradleApi()
                        }
                    }
                }
            }
        """

        file("src/integTest/java/Tester.java") << """
            import org.junit.Test;
            import org.gradle.api.file.FileType;

            public class Tester {
                @Test
                public void testGradleApiAvailability() {
                    FileType type = FileType.FILE;
                }
            }
        """

        expect:
        succeeds('integTest')
    }

    @Ignore("self-resolving methods not yet available in test suites")
    def "can add gradleTestKit dependency to the default suite"() {
        given:
        buildFile << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnitJupiter()
                        dependencies {
                            implementation gradleTestKit()
                        }
                    }
                }
            }
        """

        file('src/test/java/Tester.java') << """
            import org.gradle.testkit.runner.TaskOutcome;
            import org.junit.jupiter.api.Test;

            public class Tester {
                @Test
                public void testTestKitAvailability()  {
                    TaskOutcome result = TaskOutcome.SUCCESS;
                }
            }
        """

        expect:
        succeeds('test')
    }

    @Ignore("self-resolving methods not yet available in test suites")
    def "can add gradleTestKit dependency to a custom suite"() {
        given:
        buildFile << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    integTest(JvmTestSuite) {
                        useJUnitJupiter()
                        dependencies {
                            implementation gradleTestKit()
                        }
                    }
                }
            }
        """

        file('src/integTest/java/Tester.java') << """
            import org.gradle.testkit.runner.TaskOutcome;
            import org.junit.jupiter.api.Test;

            public class Tester {
                @Test
                public void testTestKitAvailability()  {
                    TaskOutcome result = TaskOutcome.SUCCESS;
                }
            }
        """

        expect:
        succeeds('integTest')
    }
    // endregion dependencies - self-resolving dependencies
}
