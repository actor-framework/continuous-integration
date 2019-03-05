def call(config, jobName) {
    def status = new org.caf.Status()
    status.collectAllChecks(config, jobName)
}
