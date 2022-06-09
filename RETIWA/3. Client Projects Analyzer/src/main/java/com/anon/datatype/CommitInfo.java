package com.anon.datatype;

public class CommitInfo {
    public String commitSHA;
    public String commitMessage_subject;
    public String parentCommitSHA;
    public int commitIndex, totalCommits;


    public CommitInfo(String _curCommitSHA) {
        this.commitSHA = _curCommitSHA;
        this.commitMessage_subject = null;
    }

    public CommitInfo(String _curCommitSHA, String _commitMessage_subject) {
        this.commitSHA = _curCommitSHA;
        this.commitMessage_subject = _commitMessage_subject;
    }

    public CommitInfo(String _curCommit, String _parentCommit, int _commitIndex, int _totalCommits, String _commitMessage_subject) {
        this.commitSHA = _curCommit;
        this.parentCommitSHA = _parentCommit;
        this.commitIndex = _commitIndex;
        this.totalCommits = _totalCommits;
        this.commitMessage_subject = _commitMessage_subject;
    }

    @Override
    public String toString() {
        if(parentCommitSHA!=null)
            return String.format("%s..%s (%d/%d)", parentCommitSHA.substring(0, 15), commitSHA.substring(0, 15), commitIndex, totalCommits);
        else
            return commitSHA.substring(0, 15);
    }
}
