package io.github.mangi.eta.testsupport

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Only listed native integration methods require a Unix host; pure tests in the same class still execute. */
class PosixIntegrationHostRule(vararg methods: String) : TestRule {
    private val nativeMethods = methods.toSet()
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (description.methodName.substringBefore('[') in nativeMethods) {
                assumeTrue(
                    "This integration case executes POSIX shell/filesystem or unlink-open-descriptor semantics; Linux release CI must execute it",
                    File.separatorChar == '/' && File("/bin/sh").canExecute(),
                )
            }
            base.evaluate()
        }
    }
}
