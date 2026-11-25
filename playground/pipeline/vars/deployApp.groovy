import playground.ci.Release

/** Jenkins shared-library step: `deployApp env: 'dev', version: '1.2.0'`. */
def call(Map args) {
    Release release = new Release(args.version)
    if (!release.isStable() && args.env == 'prod') {
        error "Refusing to deploy ${release.tag()} to prod"
    }
    retry(Release.ATTEMPTS) {
        sh "./deploy.sh --env ${args.env} --tag ${release.tag()}"
    }
}
