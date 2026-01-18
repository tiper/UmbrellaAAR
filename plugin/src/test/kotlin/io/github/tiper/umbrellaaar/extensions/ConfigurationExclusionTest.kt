package io.github.tiper.umbrellaaar.extensions

import io.github.tiper.umbrellaaar.extensions.mocks.MockDependency
import io.github.tiper.umbrellaaar.extensions.mocks.MockExcludeRule
import io.github.tiper.umbrellaaar.extensions.mocks.MockProject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.artifacts.ExcludeRule

class ConfigurationExclusionTest {

    @Test
    fun `excludeRule matches when both group and module match exactly`() {
        val rule = MockExcludeRule(group = "com.example", module = "library")

        assertTrue(rule.matches("com.example", "library"))
    }

    @Test
    fun `excludeRule does not match when group matches but module differs`() {
        val rule = MockExcludeRule(group = "com.example", module = "library")

        assertFalse(rule.matches("com.example", "other-library"))
    }

    @Test
    fun `excludeRule does not match when module matches but group differs`() {
        val rule = MockExcludeRule(group = "com.example", module = "library")

        assertFalse(rule.matches("org.other", "library"))
    }

    @Test
    fun `excludeRule matches any module when only group is specified`() {
        val rule = MockExcludeRule(group = "com.example", module = null)

        assertTrue(rule.matches("com.example", "library1"))
        assertTrue(rule.matches("com.example", "library2"))
        assertTrue(rule.matches("com.example", "any-module"))
    }

    @Test
    fun `excludeRule does not match different group when only group is specified`() {
        val rule = MockExcludeRule(group = "com.example", module = null)

        assertFalse(rule.matches("org.other", "library"))
    }

    @Test
    fun `excludeRule matches any group when only module is specified`() {
        val rule = MockExcludeRule(group = null, module = "library")

        assertTrue(rule.matches("com.example", "library"))
        assertTrue(rule.matches("org.other", "library"))
        assertTrue(rule.matches("any.group", "library"))
    }

    @Test
    fun `excludeRule does not match different module when only module is specified`() {
        val rule = MockExcludeRule(group = null, module = "library")

        assertFalse(rule.matches("com.example", "other-library"))
    }

    @Test
    fun `excludeRule does not match anything when both group and module are empty`() {
        val rule = MockExcludeRule(group = null, module = null)

        assertFalse(rule.matches("com.example", "library"))
        assertFalse(rule.matches(null, null))
    }

    @Test
    fun `excludeRule handles null group parameter with group-only rule`() {
        val rule = MockExcludeRule(group = "com.example", module = null)

        assertFalse(rule.matches(null, "library"))
    }

    @Test
    fun `excludeRule handles null module parameter with module-only rule`() {
        val rule = MockExcludeRule(group = null, module = "library")

        assertFalse(rule.matches("com.example", null))
    }

    @Test
    fun `excludeRule handles both null parameters`() {
        val rule = MockExcludeRule(group = "com.example", module = "library")

        assertFalse(rule.matches(null, null))
    }

    @Test
    fun `excludeRule with group-only matches when module is null`() {
        val rule = MockExcludeRule(group = "com.example", module = null)

        assertTrue(rule.matches("com.example", null))
    }

    @Test
    fun `excludeRule with module-only matches when group is null`() {
        val rule = MockExcludeRule(group = null, module = "library")

        assertTrue(rule.matches(null, "library"))
    }

    @Test
    fun `project is excluded when rule matches group and name`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = "my-library"),
        )

        assertTrue(project.isExcluded(rules))
    }

    @Test
    fun `project is not excluded when no rules match`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = listOf(
            MockExcludeRule(group = "org.other", module = "other-library"),
        )

        assertFalse(project.isExcluded(rules))
    }

    @Test
    fun `project is excluded when any rule matches`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = listOf(
            MockExcludeRule(group = "org.other", module = "other-library"),
            MockExcludeRule(group = "com.example", module = "my-library"), // This one matches
            MockExcludeRule(group = "another", module = "lib"),
        )

        assertTrue(project.isExcluded(rules))
    }

    @Test
    fun `project is excluded by group-only rule`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = null),
        )

        assertTrue(project.isExcluded(rules))
    }

    @Test
    fun `project is excluded by module-only rule`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = listOf(
            MockExcludeRule(group = null, module = "my-library"),
        )

        assertTrue(project.isExcluded(rules))
    }

    @Test
    fun `project is not excluded with empty rules list`() {
        val project = MockProject(group = "com.example", name = "my-library")
        val rules = emptyList<ExcludeRule>()

        assertFalse(project.isExcluded(rules))
    }

    @Test
    fun `dependency is excluded when rule matches group and name`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = "library"),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency is not excluded when no rules match`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = listOf(
            MockExcludeRule(group = "org.other", module = "other"),
        )

        assertFalse(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency is excluded when any rule matches`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = listOf(
            MockExcludeRule(group = "wrong", module = "wrong"),
            MockExcludeRule(group = "com.example", module = "library"), // Matches
            MockExcludeRule(group = "also", module = "wrong"),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency is excluded by group-only rule`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = null),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency is excluded by module-only rule`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = listOf(
            MockExcludeRule(group = null, module = "library"),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency is not excluded with empty rules list`() {
        val dependency = MockDependency(group = "com.example", name = "library")
        val rules = emptyList<ExcludeRule>()

        assertFalse(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency with null group is not excluded`() {
        val dependency = MockDependency(group = null, name = "library")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = "library"),
        )

        assertFalse(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency with null group is excluded by module-only rule`() {
        val dependency = MockDependency(group = null, name = "library")
        val rules = listOf(
            MockExcludeRule(group = null, module = "library"),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency with null name is not excluded`() {
        val dependency = MockDependency(group = "com.example", name = "")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = "library"),
        )

        assertFalse(dependency.isExcluded(rules))
    }

    @Test
    fun `dependency with null name is excluded by group-only rule`() {
        val dependency = MockDependency(group = "com.example", name = "")
        val rules = listOf(
            MockExcludeRule(group = "com.example", module = null),
        )

        assertTrue(dependency.isExcluded(rules))
    }

    @Test
    fun `multiple dependencies with mixed exclusion rules`() {
        val dep1 = MockDependency(group = "com.example", name = "lib1")
        val dep2 = MockDependency(group = "com.example", name = "lib2")
        val dep3 = MockDependency(group = "org.other", name = "lib3")

        val rules = listOf(
            MockExcludeRule(group = "com.example", module = "lib1"),
        )

        assertTrue(dep1.isExcluded(rules))
        assertFalse(dep2.isExcluded(rules))
        assertFalse(dep3.isExcluded(rules))
    }
}
