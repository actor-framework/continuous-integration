def call(Map config) {
    def ciLibVersion = config['ciLibVersion'] ?: 0.0
    if (ciLibVersion < 1.0)
        throw new RuntimeException('CAF CI pipeline syntax no longer supported, please use at least ciLibVersion 1.0')
    echo "fetch sources for branch ${env.GIT_BRANCH}"
    deleteDir()
    dir('sources') {
        checkout scm
        // Generate "git_diff.txt "and optionally "release.txt" for later stages.
        sh """
            if [ "${env.GIT_BRANCH}" == main ] ; then
                # on main, we simply compare to the last commit
                git diff -U0 --no-color HEAD^ > git_diff.txt
            else
                # in branches, we diff to the merge-base, because Jenkins might not see each commit individually
                git fetch --no-tags ${env.GIT_URL} +refs/heads/main:refs/remotes/origin/main
                git diff -U0 --no-color \$(git merge-base origin/main HEAD) > git_diff.txt
            fi
            if [ -f scripts/get-release-version.sh ] ; then
              sh scripts/get-release-version.sh
            fi
        """
        // Write config and normalized build matrix to disk.
        writeJSON file: 'config.json', json: config
        archiveArtifacts 'config.json'
        def pyScript = libraryResource 'org/caf/normalize-build-matrix.py'
        writeFile([
            file: 'normalize-build-matrix.py',
            text: pyScript,
        ])
        sh '''
            chmod +x normalize-build-matrix.py
            ./normalize-build-matrix.py config.json build-matrix.json
        '''
        archiveArtifacts 'build-matrix.json'
    }
    stash includes: 'sources/**', name: 'sources'
    notifyAllChecks(config, 'pending', '')
}
