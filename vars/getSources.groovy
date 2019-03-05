def call(config) {
    echo "build branch ${env.GIT_BRANCH}"
    deleteDir()
    dir('sources') {
        checkout scm
        // Prepare the git_diff.txt file needed by the 'Check Style' stage.
        sh """
            if [ "${env.GIT_BRANCH}" == master ] ; then
                # on master, we simply compare to the last commit
                git diff -U0 --no-color HEAD^ > git_diff.txt
            else
                # in branches, we diff to the merge-base, because Jenkins might not see each commit individually
                git fetch --no-tags ${env.GIT_URL} +refs/heads/master:refs/remotes/origin/master
                git diff -U0 --no-color \$(git merge-base origin/master HEAD) > git_diff.txt
            fi
        """
    }
    stash includes: 'sources/**', name: 'sources'
    notifyAllChecks(config, 'PENDING', '')
}