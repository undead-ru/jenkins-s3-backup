node() {

    def daysToStore = 7
    def backupBucketName = '' // S3 bucket name to store backup

    def cdt = System.currentTimeMillis()
    def timestampToStore = cdt - (1000*60*60*24*daysToStore + 1000*60*60) // days to store in milliseconds plus one hour
    def backupFileName = "${cdt}-jenkins-backup-${env.BUILD_ID}.tar.gz"
    def backupJobsFileName = "${cdt}-jenkins-backup-${env.BUILD_ID}-jobs.tar.gz"
    def backupFileMask = '([\\d]{13})-jenkins-backup-[\\d]+(-jobs)?.tar.gz'

    cleanWs()

    stageName = 'Create backup'
    stage (stageName) {

        // Create a backup directory
        sh "mkdir -p $BUILD_ID"

        // Copy global configuration files into backup directory
        sh "cp $JENKINS_HOME/*.xml $BUILD_ID/"

        // Copy keys and secrets into the workspace
        sh "cp $JENKINS_HOME/identity.key* $BUILD_ID/"
        sh "cp $JENKINS_HOME/secret.key $BUILD_ID/"
        sh "cp $JENKINS_HOME/secret.key.not-so-secret $BUILD_ID/"
        sh "cp -r $JENKINS_HOME/secrets $BUILD_ID/"

        // Copy additional files needed to full backup into backup directory
        sh "cp -r $JENKINS_HOME/fingerprints $BUILD_ID/"
        sh "cp -r $JENKINS_HOME/plugins $BUILD_ID/"
        sh "cp -r $JENKINS_HOME/tools $BUILD_ID/"
        sh "cp -r $JENKINS_HOME/userContent $BUILD_ID/"
        sh "cp -r $JENKINS_HOME/nodes $BUILD_ID/"

        // Copy user configuration files into backup directory
        sh "cp -r $JENKINS_HOME/users $BUILD_ID/"

        // Create jobs directory in backup
        sh "mkdir -p $BUILD_ID/jobs"

        // Copy job definitions into backup directory (small backup size, only jobs directory hierarchy and config.xml files will be stored)
        sh "rsync -am --include='config.xml' --include='*/' --prune-empty-dirs --exclude='*' $JENKINS_HOME/jobs/ $BUILD_ID/jobs/"

        // Create an archive with jenkins configuration and jobs definitions
        sh "tar czpf ${backupFileName} -C $BUILD_ID ."

        // Create an full jobs archive
        sh "tar czpf ${backupJobsFileName} -C $JENKINS_HOME/jobs/ ."
    }

    stageName = 'Push backup to S3 bucket'
    stage(stageName) {
        sh "aws s3 cp ${backupFileName} s3://${backupBucketName}/${backupFileName}"
        sh "aws s3 cp ${backupJobsFileName} s3://${backupBucketName}/${backupJobsFileName}"
    }

    stageName = 'Delete old backups in S3 bucket'
    stage(stageName) {
        def backups = sh(script: "aws s3 ls ${backupBucketName}/ | awk \'{print \$4}\'", returnStdout: true).trim().split('\n')

        if (backups.size() > (daysToStore * 2)) {
            def backupsToDelete = []
            for (file in backups) {
                def match = file =~ backupFileMask
                if (match) {
                    def timestampFromName = match[0][1] as Long
                    if (timestampFromName < timestampToStore) {
                        echo file + " is older than " + daysToStore + " days. Marked to delete."
                        backupsToDelete << file
                    }
                }
            }
            if (backupsToDelete.size() > 0) {
                for (fileToDelete in backupsToDelete) {
                    sh "aws s3 rm s3://${backupBucketName}/${fileToDelete}"
                }
            } else {
                echo "There are no backups older than " + daysToStore + " days. Nothing to delete."
            }
        } else {
            echo "There are less than " + daysToStore + " backups in S3 bucket. Nothing to delete."
        }
    }

    stageName = 'Cleanup'
    stage(stageName) {
        cleanWs()
    }
}
