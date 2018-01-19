node() {

    def backupBucketName = '' // S3 bucket name to restore backup

    def latestBackup
    def latestJobsBackup
    def latestBackupDate
    def latestJobsBackupDate
    def backupFileMask = '([\\d]{13})-jenkins-backup-[\\d]+.tar.gz'
    def backupJobsFileMask = '([\\d]{13})-jenkins-backup-[\\d]+-jobs.tar.gz'

    cleanWs()

    stageName = 'Search for latest backup on S3'
    stage(stageName) {
        def backups = []
        def backupsJobs = []
        def files = sh(script: "aws s3 ls ${backupBucketName}/ | awk \'{print \$4}\'", returnStdout: true).trim().split('\n')

        for (file in files) {
            if (file =~ backupFileMask) {
                backups << file
            }
            if (file =~ backupJobsFileMask) {
                backupsJobs << file
            }
        }

        if (backups.size() >= 1) {
            latestBackup = backups.sort()[backups.size()-1]
            def match = latestBackup =~ backupFileMask
            latestBackupDate = new Date(match[0][1] as Long)
        } else {
            currentBuild.result = 'ABORTED'
            error("No backups found in S3://${backupBucketName}")
        }
        if (backupsJobs.size() >= 1) {
            latestJobsBackup = backupsJobs.sort()[backupsJobs.size()-1]
            def match = latestBackup =~ backupFileMask
            latestJobsBackupDate = new Date(match[0][1] as Long)
        } else {
            currentBuild.result = 'ABORTED'
            error("No Jobs backups found in S3://${backupBucketName}")
        }
    }

    // wait 30 seconds for admin's decision
    stageName = "Confirm recovery (30 seconds)"
    stage(stageName) {
        timeout(time: 30, unit: 'SECONDS') {
            input "Restore Jenkins backup and Jenkins Jobs backup from\n\ns3://${backupBucketName}/${latestBackup}, created at ${latestBackupDate.toString()}\nand\ns3://${backupBucketName}/${latestJobsBackup}, created at ${latestJobsBackupDate.toString()}?"
        }
    }

    stageName = 'Get confirmed backup from S3'
    stage(stageName) {
        sh "aws s3 cp s3://${backupBucketName}/${latestBackup} ${latestBackup}"
        sh "aws s3 cp s3://${backupBucketName}/${latestJobsBackup} ${latestJobsBackup}"
    }

    stageName = 'Restoring backup from tarballs'
    stage(stageName) {
        sh "tar -C $JENKINS_HOME --overwrite -xzf ${latestBackup}"
        sh "tar -C $JENKINS_HOME/jobs --overwrite -xzf ${latestJobsBackup}"
    }

    stageName = 'Don\'t forget to restart jenkins'
    stage(stageName) {
        echo "Don't forget to restart jenkins!"
    }

    stage = 'Cleanup'
    stage(stageName) {
        cleanWs()
    }
}
