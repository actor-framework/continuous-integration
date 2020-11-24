package org.caf

// Adds additional context information to commits on GitHub.
def setBuildStatus(config, context, state, message) {
    if (!config.containsKey('repository'))
      return
    withCredentials([string(credentialsId: 'github-token', variable: 'GitHubToken')]) {
        sh([
            returnStdout: false,
            script: """
                curl https://api.github.com/repos/${config.repository}/statuses/${env.GIT_COMMIT} \
                     -H "authorization: token \$GitHubToken" \
                     -H "content-type: application/json" \
                     -X POST \
                     -d '{ "state": "$state", "description": "$message", "target_url": "${env.BUILD_URL}", "context": "$context" }' \
                     || true
            """
        ])
    }
}

// Returns the content of fileName as list of strings or an empty list if the file does not exist.
def fileLinesOrEmptyList(fileName) {
    // Using a fileExists() + readFile() approach here fails for some mysterious reason.
    sh([
        script: """if [ -f "$fileName" ] ; then cat "$fileName" ; fi""",
        returnStdout: true,
    ]).trim().tokenize('\n')
}

// Reports the status of the build itself, i.e., compiling via CMake.
def buildStatus(buildIds) {
    [
        // We always report success here, because we won't reach the final stage otherwise.
        success: true,
        summary: "All ${buildIds.size()} builds compiled",
        text: "Successfully compiled all ${buildIds.size()} builds for $PrettyJobName.",
    ]
}

// Reports the status of the unit tests check.
def testsStatus(buildIds) {
    def failed = buildIds.findAll {
        !fileExists("${it}.success")
    }
    def numBuilds = buildIds.size()
    if (failed.isEmpty())
        return [
            success: true,
            summary: "All $numBuilds builds passed the unit tests",
            text: "The unit tests succeeded on all $numBuilds builds."
        ]
    def failRate = "${failed.size()}/$numBuilds"
    [
        success: false,
        summary: "$failRate builds failed to run the unit tests",
        text: "The unit tests failed on $failRate builds:\n" + failed.collect{"- $it"}.join('\n'),
    ]
}

// Reports the status of the integration tests check.
def integrationStatus(buildIds) {
    try { unstash 'integration-result' }
    catch (Exception) { }
    def all = fileLinesOrEmptyList('all-integration-tests.txt')
    def failed = fileLinesOrEmptyList('failed-integration-tests.txt')
    if (all.isEmpty())
        return [
            success: false,
            summary: 'Unable to run integration tests',
            text: 'Unable to run integration tests!',
        ]
    def numTests = all.size()
    if (failed.isEmpty())
        return [
            success: true,
            summary: "All $numTests integration tests passed",
            text: "All $numTests integration tests passed.",
        ]
    def failRate = "${failed.size()}/$numTests"
    [
        success: false,
        summary: "$failRate integration tests failed",
        text: "The following integration tests failed ($failRate):\n" + failed.collect{"- $it"}.join('\n')
    ]
}

// Reports the status of the clang-format check.
def styleStatus(buildIds) {
    def clangFormatDiff = ''
    try {
        unstash 'clang-format-result'
        clangFormatDiff = readFile('clang-format-diff.txt')
    }
    catch (Exception) {
        return [
            success: false,
            summary: 'Unable to produce clang-format diff',
            text: 'Unable to produce clang-format diff!',
        ]
    }
    if (clangFormatDiff.isEmpty())
        return [
            success: true,
            summary: 'This patch follows our style conventions',
            text: 'This patch follows our style conventions.',
        ]
    [
        success: false,
        summary: 'This patch violates our style conventions',
        text: 'This patch violates our style conventions! See attached clang-format-diff.txt.',
        attachmentsPattern: 'clang-format-diff.txt',
    ]
}

// Reports the status of the coverage check.
def coverageStatus(buildIds) {
    try {
        unstash 'coverage-result'
        def coverageResult = readJSON('coverage.json')
        writeFile([
            file: 'coverage.txt',
            text: coverageResult['percent_covered'],
        ])
        archiveArtifacts('project-coverage.txt')
    }
    catch (Exception) { }
    if (fileExists('coverage.json'))
        return [
            success: true,
            summary: 'Generated coverage report',
            text: "The coverage report was successfully generated.",
        ]
    [
        success: false,
        summary: 'Unable to generate coverage report',
        text: 'No coverage report was produced!',
    ]
}

def collectAllChecks(config, jobName) {
    dir('tmp') {
        deleteDir()
        // Compute the list of all build IDs.
        def buildIds = []
        config['buildMatrix'].each {
            config['builds'].each {
                buildIds << "${os}_${it}"
            }
        }
        // Fetch stashed files for all builds.
        buildIds.each {
            try { unstash it }
            catch (Exception) { }
        }
        // Read all .build-info files and generate a nicely formatted artifact.
        def buildInfosIn = sh([
            script: """cat *.txt 2>/dev/null || echo""",
            returnStdout: true,
        ]).trim().tokenize('\n')
        def buildInfosOut = StringBuilder.newInstance()
        ['Compiler: ', 'CMake: '].each { prefix ->
            def lines = buildInfosIn.findAll { it.startsWith(prefix) }         \
                                    .collect { it.drop(prefix.size()) }        \
                                    .sort()                                    \
                                    .unique()
            if (!lines.isEmpty())
                buildInfosOut << prefix.trim() << '\n'                         \
                              << lines.join('\n') << '\n\n'
        }
        def buildInfosOutStr = buildInfosOut.toString()
        if (!buildInfosOutStr.isEmpty()) {
            writeFile([
                file: 'build-info.txt',
                text: buildInfosOutStr,
            ])
            archiveArtifacts('build-info.txt')
        }
        // Collect headlines and summaries for the email notification.
        def failedChecks = 0
        def headlines = []
        def texts = []
        def attachmentsPatterns = []
        config['checks'].each {
            def checkResult = "${it}Status"(buildIds)
            if (checkResult.success) {
                texts << checkResult.text
                // Don't set commit status for 'build', because Jenkins will do that anyway.
                if (it != 'build')
                    setBuildStatus(config, it, 'success', checkResult.summary)
            } else {
                failedChecks += 1
                headlines << "⛔️ $it"
                texts << checkResult.text
                setBuildStatus(config, it, 'failure', checkResult.summary)
            }
            if (checkResult.containsKey('attachmentsPattern'))
              attachmentsPatterns << checkResult.attachmentsPattern
        }
        if (headlines.isEmpty())
          headlines << "✅ success"
        // Make sure we have a newline at the end of the email.
        texts << ''
        // Send email notification.
        emailext(
            subject: "$jobName: " + headlines.join(', '),
            recipientProviders: [culprits()],
            attachLog: failedChecks > 0,
            compressLog: true,
            attachmentsPattern: attachmentsPatterns.join(','),
            body: texts.join('\n\n'),
        )
        // Set the status of this commit to unstable if any check failed to not trigger downstream jobs.
        if (failedChecks > 0)
            currentBuild.result = "UNSTABLE"
        deleteDir()
    }
}

return this
