package com.gameocr.app.ui

import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenDexBudgetTest {
    @Test fun generatedSettingsLambdasFitDexInvocationRegisterLimit() {
        val loader = javaClass.classLoader!!
        val facade = Class.forName("com.gameocr.app.ui.SettingsScreenKt", false, loader)
        val location = File(facade.protectionDomain.codeSource.location.toURI())
        val prefix = "com/gameocr/app/ui/SettingsScreenKt\$SettingsScreen\$"
        val names = if (location.isFile) {
            JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(".class") }.toList() }
        } else {
            location.walkTopDown().filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(location).invariantSeparatorsPath }
                .filter { it.startsWith(prefix) }.toList()
        }
        assertTrue("Generated settings lambdas must be inspected", names.isNotEmpty())
        names.forEach { path ->
            val type = Class.forName(path.removeSuffix(".class").replace('/', '.'), false, loader)
            type.declaredConstructors.forEach { constructor ->
                // invoke-direct/range has one byte for the argument register count, including this.
                val registers = constructor.parameterTypes.fold(1) { total, parameter ->
                    total + if (parameter == Long::class.javaPrimitiveType || parameter == Double::class.javaPrimitiveType) 2 else 1
                }
                assertTrue("${type.name} needs $registers argument registers (maximum 255)", registers <= 255)
            }
        }
    }
}
