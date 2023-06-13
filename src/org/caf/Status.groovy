package org.caf

// Adds additional context information to commits on GitHub.
def setBuildStatus(config, context, state, message) {
    if (!config.containsKey('repository'))
      return
    withCredentials([string(credentialsId: 'github-token', variable: 'GitHubToken')]) {
        sh([
            returnStdout: false,
            script: """
                curl -X POST \
                     -H "Accept: application/vnd.github+json" \
                     -H "Authorization: Bearer \$GitHubToken"\
                     https://api.github.com/repos/${config.repository}/statuses/${env.GIT_COMMIT} \
                     -d '{"state":"$state","target_url":"${env.BUILD_URL}","description":"$message","context":"continuous-integration/jenkins/$context"}' \
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
def buildStatus(numBuilds) {
    [
        // We always report success here, because we won't reach the final stage otherwise.
        success: true,
        summary: "All $numBuilds builds compiled",
        text: "Successfully compiled all $numBuilds builds for $PrettyJobName.",
    ]
}

// Reports the status of the unit tests check.
def testsStatus(numBuilds) {
    def failed = []
    for (int i = 0; i < numBuilds; ++i)
        if (!fileExists("build-${i}.success"))
            failed << i
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

// Reports the status of the clang-format check.
def styleStatus(numBuilds) {
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

// // Reports the status of the coverage check. TODO: reimplement
// def coverageStatus(buildIds) {
//     try {
//         unstash 'coverage-result'
//         def coverageResult = readJSON('coverage.json')
//         writeFile([
//             file: 'coverage.txt',
//             text: coverageResult['percent_covered'],
//         ])
//         archiveArtifacts('project-coverage.txt')
//     }
//     catch (Exception) { }
//     if (fileExists('coverage.json'))
//         return [
//             success: true,
//             summary: 'Generated coverage report',
//             text: "The coverage report was successfully generated.",
//         ]
//     [
//         success: false,
//         summary: 'Unable to generate coverage report',
//         text: 'No coverage report was produced!',
//     ]
// }

def collectAllChecks(config, jobName) {
    dir('tmp') {
        deleteDir()
        unstash 'sources'
        def buildMatrix = readJSON file: 'sources/build-matrix.json'
        int numBuilds = buildMatrix.size()
        // Fetch stashed files for all builds.
        for (int i = 0; i < numBuilds; ++i) {
            try { unstash "build-${i}" }
            catch (Exception) { }
        }
        // Read all .info files and generate a nicely formatted artifact.
        def buildInfosIn = sh([
            script: """cat *.info 2>/dev/null || echo""",
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
            def checkResult = "${it}Status"(numBuilds)
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
        if (headlines.isEmpty()) {
            if (currentBuild.result == "UNSTABLE")
                headlines << "⚠️  unstable"
            else
                headlines << "✅ success"
        }
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
